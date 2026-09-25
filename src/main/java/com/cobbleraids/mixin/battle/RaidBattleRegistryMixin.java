package com.cobbleraids.mixin.battle;

import com.cobbleraids.api.encounter.EncounterRules;
import com.cobbleraids.battle.RaidPendingRules;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.runner.ShowdownService;
import com.cobbleraids.lifecycle.RaidReconnectService;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon assigns side-1 actors p1,p3,p5... and side-2 actors p2,p4...
 * A cooperative raid instead uses contiguous player slots followed by the boss:
 * players => p1..pN, boss => p(N+1).
 *
 * This redirect also injects playerCount into the >start format JSON. The paired, version-checked
 * Showdown patch makes the Format constructor honor that value for gameType=raid.
 */
@Mixin(BattleRegistry.class)
public abstract class RaidBattleRegistryMixin {
    /** Cobblemon normally stops the entire PokemonBattle when any participating player disconnects.
     * For a raid, a disconnect withdraws only that participant and forfeits their rewards. */
    @Inject(method = "onPlayerDisconnect", at = @At("HEAD"), cancellable = true)
    private void cobbleRaids$raidDisconnect(ServerPlayer player, CallbackInfo ci) {
        boolean isRaid;
        try {
            PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
            RaidSession raid = RaidRegistry.get(battle);
            isRaid = raid != null;
            if (isRaid && raid.isActiveParticipant(player.getUUID())) {
                RaidReconnectService.onPlayerDisconnected(raid, player, ((ServerLevel) player.level()).getServer());
            }
        } catch (Exception ex) {
            // Returns without cancelling, so Cobblemon's own disconnect handling runs. That ends the
            // battle for everyone, which is bad -- but we no longer know whether this was a raid at
            // all, and leaving a real disconnect half-processed strands the actor instead.
            RaidFaultBarrier.report("mixin:onPlayerDisconnect", ex);
            return;
        }
        if (!isRaid) return;
        // Even a player who explicitly withdrew remains an actor until shared-battle cleanup;
        // never let their later disconnect invoke PokemonBattle.stop() for everyone else.
        ci.cancel();
    }

    @Redirect(
        method = "startShowdown",
        at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/battles/runner/ShowdownService;startBattle(Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;[Ljava/lang/String;)V")
    )
    private void cobbleRaids$startShowdown(ShowdownService service, PokemonBattle battle, String[] messages) {
        try {
            cobbleRaids$startShowdownOrThrow(service, battle, messages);
        } catch (Exception ex) {
            // A redirect replaces Cobblemon's own call, so failing open means making that call
            // ourselves -- otherwise the battle silently never starts and the players just stand
            // there. Actor ids may already be partly rewritten by the time we arrive here, so this
            // raid will likely misbehave and be closed out by its combat timer. That is one broken
            // raid rather than an exception unwinding through battle startup for everybody.
            RaidFaultBarrier.report("mixin:startShowdown", ex);
            service.startBattle(battle, messages);
        }
    }

    private void cobbleRaids$startShowdownOrThrow(ShowdownService service, PokemonBattle battle, String[] messages) {
        if (!"raid".equals(battle.getFormat().getBattleType().getName())) {
            service.startBattle(battle, messages);
            return;
        }

        List<BattleActor> ordered = new ArrayList<>();
        // Explicit order is essential: side 1 is the frozen player snapshot; side 2 is the boss.
        for (BattleActor actor : battle.getSide1().getActors()) ordered.add(actor);
        for (BattleActor actor : battle.getSide2().getActors()) ordered.add(actor);
        if (battle.getSide2().getActors().length != 1) {
            throw new IllegalStateException("CobbleRaids currently requires exactly one boss actor");
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            BattleActor actor = ordered.get(i);
            String oldId = actor.getShowdownId();
            String newId = "p" + (i + 1);
            if (oldId != null) mapping.put(oldId, newId);
            actor.setShowdownId(newId);
        }

        String[] rewritten = new String[messages.length];
        for (int i = 0; i < messages.length; i++) {
            String line = messages[i];
            if (line == null) { rewritten[i] = null; continue; }
            String result = rewriteActorTokens(line, mapping);
            if (result.startsWith(">start ")) result = withRaidPlayerCount(result, ordered.size());
            rewritten[i] = result;
        }
        service.startBattle(battle, rewritten);
    }

    private static String rewriteActorTokens(String input, Map<String, String> mapping) {
        String result = input;
        Map<String, String> placeholders = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String placeholder = "__COBBLERAIDS_ACTOR_" + index++ + "__";
            Pattern token = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(entry.getKey()) + "(?![A-Za-z0-9_])");
            result = token.matcher(result).replaceAll(Matcher.quoteReplacement(placeholder));
            placeholders.put(placeholder, entry.getValue());
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    /**
     * Writes the raid's shape into the {@code >start} payload's format object.
     *
     * <p>{@code playerCount} is what lets the Showdown patch build 1..N sides instead of assuming
     * p1/p2. The field conditions ride the same road: an owner asking for rain sets
     * {@code raidWeather}, and raid-patch.js applies it once the battle exists. Going through the
     * format rather than sending a separate message keeps it in the one payload that is already
     * version-checked against the patch, so a mismatch is a loud failure rather than weather that
     * silently never arrives.
     *
     * <p>The ids are safe to interpolate because {@link EncounterRules} refuses anything that is
     * not a plain Showdown id -- Gson would escape a quote, but a corrupted format object would
     * take the whole battle down rather than one setting.
     */
    private static String withRaidPlayerCount(String startLine, int playerCount) {
        String json = startLine.substring(">start ".length());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject format = root.getAsJsonObject("format");
        if (format == null || !"raid".equals(format.get("gameType").getAsString())) {
            throw new IllegalStateException("Raid battle start payload is missing gameType=raid: " + startLine);
        }
        format.addProperty("playerCount", playerCount);
        EncounterRules rules = RaidPendingRules.current();
        rules.weather().ifPresent(id -> format.addProperty("raidWeather", id));
        rules.terrain().ifPresent(id -> format.addProperty("raidTerrain", id));
        return ">start " + root;
    }
}
