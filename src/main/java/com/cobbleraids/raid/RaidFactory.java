package com.cobbleraids.raid;

import com.cobbleraids.battle.RaidBattleType;
import com.cobbleraids.battle.RaidBossBattleActor;
import com.cobbleraids.battle.RaidBossBattleAI;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Turns a frozen recruitment snapshot and the physical wild boss into one shared Cobblemon raid battle. */
public final class RaidFactory {
    private RaidFactory() {}

    public static RaidSession startFromWildBoss(Collection<ServerPlayer> players, RaidDefinition definition,
                                                 PokemonEntity bossEntity, long raidMaxHealth) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(bossEntity, "bossEntity");
        if (!RaidBossEntityMarker.isRaidBoss(bossEntity)) throw new IllegalArgumentException("Boss entity is not marked as a CobbleRaids boss");
        if (bossEntity.isRemoved() || bossEntity.isBattling()) throw new IllegalStateException("Boss is unavailable or already battling");
        if (players == null || players.isEmpty()) throw new IllegalArgumentException("Raid requires at least one player");

        List<ServerPlayer> ordered = List.copyOf(players);
        if (ordered.size() > definition.recruitment().maxPlayers()) throw new IllegalArgumentException("Participant snapshot exceeds raid capacity");
        List<BattleActor> playerActors = new ArrayList<>();
        for (ServerPlayer player : ordered) {
            if (player.level() != bossEntity.level()) throw new IllegalStateException("Raid participants must be in the boss dimension");
            if (Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingPlayer(player) != null)
                throw new IllegalStateException("Player is already in a battle: " + player.getGameProfile().getName());
            var team = Cobblemon.INSTANCE.getStorage().getParty(player).toBattleTeam(true, false);
            if (team.isEmpty()) throw new IllegalStateException("Player has no battle-capable Pokémon: " + player.getGameProfile().getName());
            playerActors.add(new PlayerBattleActor(player.getUUID(), team));
        }

        BattlePokemon bossPokemon = new BattlePokemon(
                bossEntity.getPokemon(), bossEntity.getPokemon(), Collections.emptyList(), Collections.emptyList());
        RaidBossBattleActor bossActor = new RaidBossBattleActor(
                bossEntity.getPokemon().getUuid(), bossPokemon, new RaidBossBattleAI());

        BattleFormat format = new BattleFormat("cobblemon", RaidBattleType.INSTANCE, Collections.emptySet(), 9, 0);
        BattleStartResult result = BattleRegistry.startBattle(
                format,
                new BattleSide(playerActors.toArray(BattleActor[]::new)),
                new BattleSide(bossActor),
                false
        );
        if (!(result instanceof SuccessfulBattleStart successful)) throw new IllegalStateException("Unable to start raid: " + result);

        PokemonBattle battle = successful.getBattle();
        RaidSession session = new RaidSession(
                battle,
                ordered,
                Math.max(1f, (float) raidMaxHealth),
                bossEntity,
                bossActor.getUuid(),
                definition.id(),
                definition.timeLimitSeconds(),
                definition.allowFlee()
        );
        RaidRegistry.bind(session);
        session.activate();
        return session;
    }
}
