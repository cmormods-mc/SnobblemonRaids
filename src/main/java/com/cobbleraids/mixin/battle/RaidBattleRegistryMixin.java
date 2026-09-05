package com.cobbleraids.mixin.battle;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.runner.ShowdownService;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
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
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        RaidSession raid = RaidRegistry.get(battle);
        if (raid == null) return;
        if (raid.isActiveParticipant(player.getUUID())) {
            RaidLifecycleCoordinator.onPlayerDisconnected(raid, player.getUUID());
        }
        // Even a player who explicitly withdrew remains an actor until shared-battle cleanup;
        // never let their later disconnect invoke PokemonBattle.stop() for everyone else.
        ci.cancel();
    }

    @Redirect(
        method = "startShowdown",
        at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/battles/runner/ShowdownService;startBattle(Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;[Ljava/lang/String;)V")
    )
    private void cobbleRaids$startShowdown(ShowdownService service, PokemonBattle battle, String[] messages) {
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

    private static String withRaidPlayerCount(String startLine, int playerCount) {
        String json = startLine.substring(">start ".length());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject format = root.getAsJsonObject("format");
        if (format == null || !"raid".equals(format.get("gameType").getAsString())) {
            throw new IllegalStateException("Raid battle start payload is missing gameType=raid: " + startLine);
        }
        format.addProperty("playerCount", playerCount);
        return ">start " + root;
    }
}
