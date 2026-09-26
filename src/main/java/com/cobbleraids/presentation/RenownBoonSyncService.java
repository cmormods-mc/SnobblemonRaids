package com.cobbleraids.presentation;

import com.cobbleraids.network.RenownBoonSyncPayload;
import com.cobbleraids.renown.RaidRenownMarker;
import com.cobbleraids.renown.RenownBoon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Tells clients which boon a renowned raid boss has, over a dedicated packet rather than trying to
 * ride along on the boss's display name.
 *
 * <p>{@code PokemonEntity.setCustomName} flattens whatever {@code Component} it is given down to a
 * bare string before storing it, discarding every {@code Style} attribute -- which is exactly where
 * an earlier version of this feature tried to hide the boon marker. See
 * {@link com.cobbleraids.renown.RenownBoon} and {@link RaidBossNameplate} for what still rides on
 * the (flattened) text itself; this service exists for the one piece of data that cannot.
 *
 * <p>A thin static façade over {@link TrackedBossService}, which owns the register/recover/prune/
 * tick plumbing this service used to duplicate with {@link RaidBossGlowService}. There is no public
 * "a player started tracking this entity" event in this Fabric API version, so instead of trying to
 * catch that moment exactly, this resends the tiny payload to every current tracker once a second
 * for as long as the boss lives -- self-healing against a late join, a reconnect, or a player
 * walking into range, at the cost of one UUID and one short string per tracked renowned boss per
 * second.
 */
public final class RenownBoonSyncService {
    private static final Tracker TRACKER = new Tracker();

    private RenownBoonSyncService() {}

    /** Called once, right after a renowned boss is created. Does nothing for an ordinary boss. */
    public static void register(PokemonEntity boss, ServerLevel level, RenownBoon boon) {
        if (boon == null) return;
        TRACKER.register(boss, level, boon);
    }

    public static void onServerStopping(MinecraftServer server) {
        TRACKER.onServerStopping(server);
    }

    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        TRACKER.onEntityLoaded(entity, level);
    }

    public static void onEntityUnloaded(Entity entity, ServerLevel level) {
        TRACKER.onEntityUnloaded(entity, level);
    }

    public static void tick(MinecraftServer server) {
        TRACKER.tick(server);
    }

    private static void sendTo(Iterable<ServerPlayer> players, PokemonEntity boss, RenownBoon boon) {
        RenownBoonSyncPayload payload = new RenownBoonSyncPayload(boss.getPokemon().getUuid(), boon.encode());
        for (ServerPlayer player : players) {
            if (ServerPlayNetworking.canSend(player, RenownBoonSyncPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static final class Tracker extends TrackedBossService<RenownBoon> {
        Tracker() {
            super("boon-sync service");
        }

        /**
         * The boon survives on the entity regardless of this service's own tracking, as scoreboard
         * tags (see {@link RaidRenownMarker}), so a boss that loaded untracked recovers it from
         * there rather than losing it until it is destroyed and respawned.
         */
        @Override
        protected Optional<RenownBoon> recoverPayload(PokemonEntity pokemon) {
            return RaidRenownMarker.read(pokemon).map(renown -> renown.boon());
        }

        @Override
        protected void refresh(MinecraftServer server, PokemonEntity boss, RenownBoon boon) {
            sendTo(PlayerLookup.tracking(boss), boss, boon);
        }

        /** Sent immediately too, not just on the next tick, so a fresh spawn's tracker sees it without delay. */
        @Override
        protected void onRegistered(PokemonEntity boss, RenownBoon boon) {
            sendTo(PlayerLookup.tracking(boss), boss, boon);
        }
    }
}
