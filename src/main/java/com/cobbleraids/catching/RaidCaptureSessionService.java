package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.network.CaptureDetailsPayload;
import com.cobbleraids.network.CaptureOfferPayload;
import com.cobbleraids.network.CapturePulseResultPayload;
import com.cobbleraids.network.CaptureResultPayload;
import com.cobbleraids.pokemon.PartyPcDelivery;
import com.cobbleraids.reward.points.RaidPointsStore;
import com.cobbleraids.title.TitleService;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-authoritative Raid Capture Protocol engine: offers each victor a session at victory, waits
 * for them to actually claim that raid's reward, then runs their timing minigame and performs the
 * one and only capture roll.
 *
 * <p>Deliberately does not touch the reward reveal/claim flow at all -- item rewards are granted
 * exactly as they always have been, on the player's own schedule. The only thing this intercepts is
 * that specific claim's computed Raid Points: {@link #onRewardClaimed} is called from
 * {@code RaidRewardGrantEngine.awardPoints} right before it would credit them, and if this player has
 * a session waiting on this exact raid, the points are withheld and banked into the session instead
 * of credited, and only now -- after the claim, never before -- is the player actually shown the
 * capture choice. Declining, losing, or letting the choice lapse credits the banked points normally;
 * succeeding never credits them at all, which is what "replaces this raid's Raid Points" means here:
 * they are never granted in the first place, not granted-then-clawed-back.
 *
 * <p>Only sessions that are not yet fully settled live in {@link #SESSIONS}. A session is removed the
 * instant its outcome is fully spent -- its banked points credited, or its rolled Pokemon actually
 * delivered. A player's queue can hold more than one session if they win a second raid before
 * finishing the first's minigame (or before even claiming it); every player-driven packet
 * (choice/pulse/throw) acts on the oldest session actually awaiting one, skipping past both an
 * unclaimed {@code AWAITING_CLAIM} session and a resolved-but-undelivered one, since neither blocks
 * whatever is queued behind it.
 *
 * <p>A pulse or throw is scored from when the server itself receives the input packet relative to
 * when that step's travel window began ({@link RaidCaptureSession#currentStepStartedAtEpochMs()}),
 * never from a client-reported timestamp -- the server is the only clock this mechanic trusts, and
 * ordinary network latency is absorbed the same way it would be for a player who simply reacted a
 * little late, rather than needing a separate tolerance window.
 */
public final class RaidCaptureSessionService {
    private static final Map<UUID, ArrayDeque<RaidCaptureSession>> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_RETRY_ATTEMPT = new ConcurrentHashMap<>();

    private RaidCaptureSessionService() {}

    // ---------------------------------------------------------------- lifecycle

    public static void onServerStarted(MinecraftServer server) {
        SESSIONS.clear();
        LAST_RETRY_ATTEMPT.clear();
        SESSIONS.putAll(RaidCaptureSessionStore.get(server).take());
        int sessions = SESSIONS.values().stream().mapToInt(ArrayDeque::size).sum();
        if (sessions > 0) {
            RaidLog.info("Restored " + sessions + " raid capture session(s) for "
                    + SESSIONS.size() + " player(s).");
        }
    }

    /** Retries a delivery still waiting on party/PC room, immediately rather than on the next sweep. */
    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        synchronized (lockFor(player.getUUID())) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(player.getUUID());
            if (queue == null) return;
            for (RaidCaptureSession session : List.copyOf(queue)) {
                if (isPendingRetry(session)) deliverToOnlinePlayer(player.getServer(), queue, session, player);
            }
            pruneIfEmpty(player.getUUID(), queue);
        }
    }

    /** One player's sessions, front of queue first. For admin visibility; never mutates anything. */
    public static List<RaidCaptureSession> pendingFor(UUID playerId) {
        ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(playerId);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    /** Player ids that currently hold at least one session, resolved or not. */
    public static Set<UUID> playersWithSessions() {
        return Set.copyOf(SESSIONS.keySet());
    }

    /**
     * Drops every session for one player without settling any of them -- neither RP nor a rolled
     * Pokemon is granted for whatever this discards. Support-only: for a session stuck on a definition
     * that no longer resolves, or any other case an operator needs to unstick a player rather than
     * wait out a deadline. Returns how many were removed.
     */
    public static int clearPending(UUID playerId, MinecraftServer server) {
        Object lock = lockFor(playerId);
        synchronized (lock) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.remove(playerId);
            LOCKS.remove(playerId);
            LAST_RETRY_ATTEMPT.remove(playerId);
            int removed = queue == null ? 0 : queue.size();
            if (removed > 0) persistNow(server);
            return removed;
        }
    }

    public static void onServerStopped() {
        SESSIONS.clear();
        LOCKS.clear();
        LAST_RETRY_ATTEMPT.clear();
    }

    private static void persistNow(MinecraftServer server) {
        if (server == null) return;
        RaidCaptureSessionStore.get(server).update(SESSIONS);
        RaidFaultBarrier.guard("capture-session-flush", () -> server.overworld().getDataStorage().save());
    }

    private static Object lockFor(UUID playerId) {
        return LOCKS.computeIfAbsent(playerId, ignored -> new Object());
    }

    // ---------------------------------------------------------------- offering

    /**
     * Creates a (not yet presented) capture session for every active participant, online or not --
     * matching how RP itself is queued for every participant regardless of whether they are online at
     * the exact instant of victory, since a player mid-reconnect-grace can still be an active
     * participant. Nothing is shown to the player yet; that waits on {@link #onRewardClaimed}.
     */
    public static void offer(MinecraftServer server, RaidDefinition definition, Pokemon bossPokemon,
                             UUID raidId, Set<UUID> victors) {
        if (server == null || definition == null || bossPokemon == null || victors.isEmpty()) return;
        CobbleRaidsConfig.Catching config = CobbleRaidsConfigManager.get().catching();
        if (!config.enabled() || config.isNoOp()) return;

        var tier = definition.rarityTier();
        if (config.ceilingFor(tier) <= 0.0) return;

        ResourceLocation species = bossPokemon.getSpecies().getResourceIdentifier();
        String form = bossPokemon.getForm() == null ? null : bossPokemon.getForm().getName();
        int level = bossPokemon.getLevel();
        boolean shiny = bossPokemon.getShiny();

        for (UUID playerId : victors) {
            RaidCaptureSession session = RaidCaptureSession.offer(playerId, raidId, definition.id(), tier,
                    species, form, level, shiny);
            SESSIONS.computeIfAbsent(playerId, ignored -> new ArrayDeque<>()).addLast(session);
        }
        persistNow(server);
    }

    /**
     * Called from {@code RaidRewardGrantEngine.awardPoints}, right before it would credit
     * {@code computedRaidPoints} for this claim. Returns {@code true} if this player has an
     * {@code AWAITING_CLAIM} session for exactly this raid -- meaning the caller must not credit the
     * points itself, they are now banked in the session and the player has just been shown the
     * capture choice instead. Returns {@code false} for every ordinary claim (no session, or one
     * already past this point), meaning the caller should credit normally.
     */
    public static boolean onRewardClaimed(ServerPlayer player, UUID raidId, int computedRaidPoints) {
        if (player == null) return false;
        MinecraftServer server = player.getServer();
        synchronized (lockFor(player.getUUID())) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(player.getUUID());
            RaidCaptureSession session = findAwaitingClaim(queue, raidId);
            if (session == null) return false;

            CobbleRaidsConfig.Catching config = CobbleRaidsConfigManager.get().catching();
            long now = System.currentTimeMillis();
            RaidCaptureSession claimed = session.withClaimed(computedRaidPoints, now, config.choiceWindowSeconds());
            replaceInQueue(queue, session, claimed);
            persistNow(server);

            if (ServerPlayNetworking.canSend(player, CaptureOfferPayload.TYPE)) {
                CobbleRaidsConfig.CaptureTierConfig tierConfig = config.tierConfigFor(claimed.tier());
                // Optional display metadata only -- the offer/input/result protocol below is unchanged
                // whether or not this is sent, or whether the client's build even recognises it.
                if (ServerPlayNetworking.canSend(player, CaptureDetailsPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new CaptureDetailsPayload(raidId, claimed.species(),
                            tierConfig.baseChance(), tierConfig.stabilizationCap(), tierConfig.throwCap(),
                            config.sequenceTimeoutSeconds()));
                }
                ServerPlayNetworking.send(player, new CaptureOfferPayload(raidId, claimed.definitionId(),
                        claimed.tier().serializedName(), speciesDisplayName(claimed.species()), claimed.shiny(),
                        computedRaidPoints, config.choiceWindowSeconds(), tierConfig.pulseTravelDurationMs(),
                        tierConfig.pulseGoodZoneWidthMs(), tierConfig.pulsePerfectZoneWidthMs(),
                        tierConfig.throwTravelDurationMs(), tierConfig.throwGoodZoneWidthMs(),
                        tierConfig.throwPerfectZoneWidthMs()));
            }
            return true;
        }
    }

    private static RaidCaptureSession findAwaitingClaim(ArrayDeque<RaidCaptureSession> queue, UUID raidId) {
        if (queue == null) return null;
        for (RaidCaptureSession session : queue) {
            if (session.phase() == RaidCaptureSession.CapturePhase.AWAITING_CLAIM && session.raidId().equals(raidId)) {
                return session;
            }
        }
        return null;
    }

    private static String speciesDisplayName(ResourceLocation species) {
        Species resolved = PokemonSpecies.getByIdentifier(species);
        return resolved != null ? resolved.getTranslatedName().getString() : species.getPath();
    }

    // ---------------------------------------------------------------- player-driven transitions

    public static void handleChoice(ServerPlayer player, UUID raidIdEcho, boolean attempt) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        synchronized (lockFor(player.getUUID())) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(player.getUUID());
            RaidCaptureSession session = firstActionable(queue);
            if (session == null || session.phase() != RaidCaptureSession.CapturePhase.AWAITING_CHOICE) return;
            warnOnRaidIdMismatch(player, raidIdEcho, session.raidId());

            if (attempt) {
                CobbleRaidsConfig.Catching config = CobbleRaidsConfigManager.get().catching();
                RaidCaptureSession started = session.withAttemptStarted(System.currentTimeMillis(),
                        config.sequenceTimeoutSeconds());
                replaceInQueue(queue, session, started);
                persistNow(server);
            } else {
                RaidCaptureSession declined = session.withDeclined();
                replaceInQueue(queue, session, declined);
                persistNow(server);
                settle(server, queue, declined);
            }
        }
    }

    public static void handlePulse(ServerPlayer player, UUID raidIdEcho, int pulseIndexEcho) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        synchronized (lockFor(player.getUUID())) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(player.getUUID());
            RaidCaptureSession session = firstActionable(queue);
            if (session == null || session.phase() != RaidCaptureSession.CapturePhase.STABILIZING) return;
            if (pulseIndexEcho != session.nextPulseIndex()) return; // stale, replayed, or out-of-order
            warnOnRaidIdMismatch(player, raidIdEcho, session.raidId());

            long now = System.currentTimeMillis();
            CobbleRaidsConfig.CaptureTierConfig tierConfig =
                    CobbleRaidsConfigManager.get().catching().tierConfigFor(session.tier());
            double score = judgePulse(session, tierConfig, now);
            RaidCaptureSession updated = session.withPulseScored(score, now);
            replaceInQueue(queue, session, updated);
            persistNow(server);
            if (ServerPlayNetworking.canSend(player, CapturePulseResultPayload.TYPE)) {
                ServerPlayNetworking.send(player,
                        new CapturePulseResultPayload(session.raidId(), pulseIndexEcho, score));
            }
        }
    }

    public static void handleThrow(ServerPlayer player, UUID raidIdEcho) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        synchronized (lockFor(player.getUUID())) {
            ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(player.getUUID());
            RaidCaptureSession session = firstActionable(queue);
            if (session == null || session.phase() != RaidCaptureSession.CapturePhase.AWAITING_THROW) return;
            warnOnRaidIdMismatch(player, raidIdEcho, session.raidId());

            long now = System.currentTimeMillis();
            CobbleRaidsConfig.CaptureTierConfig tierConfig =
                    CobbleRaidsConfigManager.get().catching().tierConfigFor(session.tier());
            long offset = RaidCaptureMath.offsetFromNearestCenter(now, session.currentStepStartedAtEpochMs(),
                    tierConfig.throwTravelDurationMs());
            double throwScore = RaidCaptureMath.judge(offset, tierConfig.throwGoodZoneWidthMs(),
                    tierConfig.throwPerfectZoneWidthMs());

            RaidCaptureSession resolved = rollAndAttachPokemon(server, session, throwScore, tierConfig);
            replaceInQueue(queue, session, resolved);
            persistNow(server);
            settle(server, queue, resolved);
        }
    }

    private static double judgePulse(RaidCaptureSession session, CobbleRaidsConfig.CaptureTierConfig tierConfig, long now) {
        long offset = RaidCaptureMath.offsetFromNearestCenter(now, session.currentStepStartedAtEpochMs(),
                tierConfig.pulseTravelDurationMs());
        return RaidCaptureMath.judge(offset, tierConfig.pulseGoodZoneWidthMs(), tierConfig.pulsePerfectZoneWidthMs());
    }

    private static void warnOnRaidIdMismatch(ServerPlayer player, UUID echoed, UUID actual) {
        if (!actual.equals(echoed)) {
            RaidLog.error("Capture packet raid id mismatch for " + player.getGameProfile().getName()
                    + ": client echoed " + echoed + ", acting on active session " + actual + " instead.");
        }
    }

    // ---------------------------------------------------------------- rolling and settlement

    private static RaidCaptureSession rollAndAttachPokemon(MinecraftServer server, RaidCaptureSession session,
                                                            double throwScore, CobbleRaidsConfig.CaptureTierConfig tierConfig) {
        double stabQuality = session.stabilizationQuality();
        double finalChance = RaidCaptureMath.finalChance(tierConfig.baseChance(), tierConfig.stabilizationCap(),
                tierConfig.throwCap(), stabQuality, throwScore);
        boolean succeeded = ThreadLocalRandom.current().nextDouble() < finalChance;
        RaidCaptureSession resolved = session.withThrowResolved(throwScore, succeeded, finalChance);
        return succeeded ? attachCapturedPokemon(server, resolved) : resolved;
    }

    /**
     * Builds and serializes the rolled Pokemon once, at the instant the roll succeeds, so a later
     * delivery retry always hands over the exact same individual rather than rolling a fresh one.
     * Species resolution failing here would mean a species that was, moments ago, a live raid boss no
     * longer resolves at all -- unreachable in practice, so it is handled the same way this codebase
     * treats its other unreachable delivery cases (see {@code ShopPurchaseService.givePokemon}): logged
     * loudly, the roll itself stands (the player is not charged twice for a mechanical failure), and
     * there is nothing to retry because nothing could be built.
     *
     * <p>The trophy is recorded here too -- at roll-success, not at delivery -- the same "recorded at
     * the triggering event" reasoning win/title history already follows, so a capture whose delivery
     * is still {@code PENDING_RETRY} (party and PC both full) still shows up in the trophy room right
     * away rather than waiting on storage to free up.
     */
    private static RaidCaptureSession attachCapturedPokemon(MinecraftServer server, RaidCaptureSession resolved) {
        Pokemon built = buildCapturedPokemon(resolved);
        if (built == null) {
            RaidLog.error("Capture roll succeeded for " + resolved.playerId() + " but " + resolved.species()
                    + " no longer resolves as a species; nothing could be built.");
            return resolved;
        }
        built.heal();
        int ivPercent = BossSnapshotService.percentOf(built.getIvs().total(), 186);
        int evPercent = BossSnapshotService.percentOf(built.getEvs().total(), 510);
        int stabilizationPercent = (int) Math.round(resolved.stabilizationQuality() * 100.0);
        long now = System.currentTimeMillis();
        RaidFaultBarrier.guard("trophy-ledger:capture", () -> TrophyLedger.recordCapture(server, resolved.playerId(),
                resolved.species(), resolved.level(), resolved.shiny(), ivPercent, evPercent, resolved.tier(),
                stabilizationPercent, now));
        CompoundTag nbt = built.saveToNBT(server.registryAccess(), new CompoundTag());
        return resolved.withPokemonNbt(nbt);
    }

    private static Pokemon buildCapturedPokemon(RaidCaptureSession session) {
        Species species = PokemonSpecies.getByIdentifier(session.species());
        if (species == null) return null;
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(session.level());

        PokemonProperties overlay = new PokemonProperties();
        boolean any = false;
        // Only what this snapshot actually pinned is set; everything else -- IVs, EVs, nature,
        // ability -- keeps Cobblemon's own ordinary roll, the same "ordinary stats" recipe
        // ShopPurchaseService.build uses for a catalogue purchase, deliberately not the exact-clone
        // recipe the unrelated Personal Boss Shop buy-back uses for the same defeated boss.
        if (session.shiny()) { overlay.setShiny(Boolean.TRUE); any = true; }
        if (session.form() != null) { overlay.setForm(session.form()); any = true; }
        if (any) overlay.apply(pokemon);
        return pokemon;
    }

    private static void settle(MinecraftServer server, ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession resolved) {
        boolean wasAttempt = resolved.rolled();
        RaidCaptureSession finalState = resolved.rollSucceeded()
                ? settleCapture(server, queue, resolved)
                : settleDecline(server, queue, resolved);
        // A decline never rolled, so it gets no capture-result reveal at all -- the player already
        // saw their items and the (already-computed) RP figure at claim time, and that RP simply
        // gets credited now, same as if they had never been offered a choice at all. An actual
        // attempt (won or lost) always gets a reveal, sent only now -- after settlement -- so the
        // delivery field reflects where the Pokemon actually ended up, not the placeholder it started
        // at. The roll itself was already durably persisted well before this point.
        if (wasAttempt) sendResultIfOnline(server, finalState);
    }

    private static void sendResultIfOnline(MinecraftServer server, RaidCaptureSession resolved) {
        ServerPlayer player = server.getPlayerList().getPlayer(resolved.playerId());
        if (player == null || !ServerPlayNetworking.canSend(player, CaptureResultPayload.TYPE)) return;
        double stabQuality = resolved.stabilizationQuality() * 100.0;
        double throwQuality = resolved.throwScore() * 100.0;
        ServerPlayNetworking.send(player, new CaptureResultPayload(resolved.raidId(), resolved.rollSucceeded(),
                resolved.finalChanceUsed() * 100.0, stabQuality, throwQuality, resolved.delivery().name(),
                speciesDisplayName(resolved.species()), resolved.shiny()));
    }

    /**
     * Credits the points that were withheld at claim time. There is no failure path here worth
     * guarding against a missing definition or a stale reward config the way the old bank-a-whole-
     * claim design needed to -- the points figure was already fully computed and fixed the moment it
     * was banked, so crediting it is just a balance update, not a re-resolution of anything.
     */
    private static RaidCaptureSession settleDecline(MinecraftServer server, ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession resolved) {
        if (resolved.bankedRaidPoints() > 0) {
            RaidFaultBarrier.guard("capture-decline:raid-points",
                    () -> RaidPointsStore.award(server, resolved.playerId(), resolved.bankedRaidPoints()));
        }
        removeFromQueue(queue, resolved);
        persistNow(server);
        return resolved;
    }

    private static RaidCaptureSession settleCapture(MinecraftServer server, ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession resolved) {
        ServerPlayer player = server.getPlayerList().getPlayer(resolved.playerId());
        if (player == null) return markPendingRetry(server, queue, resolved);
        return deliverToOnlinePlayer(server, queue, resolved, player);
    }

    private static RaidCaptureSession deliverToOnlinePlayer(MinecraftServer server, ArrayDeque<RaidCaptureSession> queue,
                                                            RaidCaptureSession resolved, ServerPlayer player) {
        Pokemon toDeliver;
        try {
            toDeliver = Pokemon.Companion.loadFromNBT(server.registryAccess(), resolved.pokemonNbt());
        } catch (RuntimeException ex) {
            RaidLog.error("Capture session for " + player.getGameProfile().getName() + " ("
                    + resolved.species() + ") failed to deserialize its rolled Pokemon", ex);
            removeFromQueue(queue, resolved);
            persistNow(server);
            return resolved;
        }

        PartyPcDelivery.Outcome outcome = PartyPcDelivery.tryDeliver(toDeliver, player);
        if (outcome == PartyPcDelivery.Outcome.NO_ROOM) {
            RaidCaptureSession updated = markPendingRetry(server, queue, resolved);
            player.sendSystemMessage(Component.literal(
                            "You caught the raid boss, but your party and PC are both full. It will be "
                                    + "delivered as soon as you have room.")
                    .withStyle(ChatFormatting.YELLOW));
            return updated;
        }

        RaidPlayerRecords.recordCatch(server, resolved.playerId());
        RaidFaultBarrier.guard("title-unlock:catch", () -> TitleService.checkUnlocks(server, resolved.playerId()));
        removeFromQueue(queue, resolved);
        persistNow(server);
        player.sendSystemMessage(Component.literal(
                        "You caught the raid boss! It replaces this raid's Raid Points.")
                .withStyle(ChatFormatting.GOLD));
        return resolved.withDelivery(RaidCaptureSession.DeliveryState.DELIVERED);
    }

    private static RaidCaptureSession markPendingRetry(MinecraftServer server, ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession resolved) {
        RaidCaptureSession updated = resolved.withDelivery(RaidCaptureSession.DeliveryState.PENDING_RETRY);
        replaceInQueue(queue, resolved, updated);
        persistNow(server);
        return updated;
    }

    // ---------------------------------------------------------------- tick sweep

    /** Expires overdue choices/sequences at zero bonus, and retries any pending delivery. */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        long now = System.currentTimeMillis();
        CobbleRaidsConfig.Catching config = CobbleRaidsConfigManager.get().catching();

        for (UUID playerId : List.copyOf(SESSIONS.keySet())) {
            synchronized (lockFor(playerId)) {
                ArrayDeque<RaidCaptureSession> queue = SESSIONS.get(playerId);
                if (queue == null) continue;

                List<RaidCaptureSession> expired = new ArrayList<>();
                for (RaidCaptureSession session : queue) {
                    if (session.isExpired(now)) expired.add(session);
                }
                for (RaidCaptureSession session : expired) {
                    RaidCaptureSession resolved = resolveExpired(server, session, now, config);
                    replaceInQueue(queue, session, resolved);
                    persistNow(server);
                    settle(server, queue, resolved);
                }

                retryPendingDeliveries(server, playerId, queue);
                pruneIfEmpty(playerId, queue);
            }
        }
    }

    /** A missed choice declines; a missed sequence step zero-fills, exactly as an actually-missed input would. */
    private static RaidCaptureSession resolveExpired(MinecraftServer server, RaidCaptureSession session,
                                                      long now, CobbleRaidsConfig.Catching config) {
        if (session.phase() == RaidCaptureSession.CapturePhase.AWAITING_CHOICE) {
            return session.withDeclined();
        }
        RaidCaptureSession filled = session;
        while (filled.nextPulseIndex() < 3) filled = filled.withPulseScored(0.0, now);
        CobbleRaidsConfig.CaptureTierConfig tierConfig = config.tierConfigFor(filled.tier());
        return rollAndAttachPokemon(server, filled, 0.0, tierConfig);
    }

    private static void retryPendingDeliveries(MinecraftServer server, UUID playerId, ArrayDeque<RaidCaptureSession> queue) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        long now = System.currentTimeMillis();
        long intervalMs = CobbleRaidsConfigManager.get().catching().deliveryRetryIntervalSeconds() * 1000L;
        Long last = LAST_RETRY_ATTEMPT.get(playerId);
        if (last != null && now - last < intervalMs) return;
        LAST_RETRY_ATTEMPT.put(playerId, now);

        for (RaidCaptureSession session : List.copyOf(queue)) {
            if (isPendingRetry(session)) deliverToOnlinePlayer(server, queue, session, player);
        }
    }

    private static boolean isPendingRetry(RaidCaptureSession session) {
        return session.phase() == RaidCaptureSession.CapturePhase.RESOLVED
                && session.delivery() == RaidCaptureSession.DeliveryState.PENDING_RETRY;
    }

    // ---------------------------------------------------------------- queue plumbing

    /**
     * The oldest session actually awaiting a player action -- skipping past an unclaimed
     * {@code AWAITING_CLAIM} session (nothing to show the player yet) and a resolved-but-undelivered
     * one (nothing left for the player to do), neither of which may block whatever is queued behind
     * them.
     */
    private static RaidCaptureSession firstActionable(ArrayDeque<RaidCaptureSession> queue) {
        if (queue == null) return null;
        for (RaidCaptureSession session : queue) {
            switch (session.phase()) {
                case AWAITING_CHOICE, STABILIZING, AWAITING_THROW -> { return session; }
                default -> { /* keep looking */ }
            }
        }
        return null;
    }

    private static void replaceInQueue(ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession target, RaidCaptureSession replacement) {
        List<RaidCaptureSession> rebuilt = new ArrayList<>(queue.size());
        for (RaidCaptureSession session : queue) rebuilt.add(session == target ? replacement : session);
        queue.clear();
        queue.addAll(rebuilt);
    }

    private static void removeFromQueue(ArrayDeque<RaidCaptureSession> queue, RaidCaptureSession session) {
        queue.removeIf(candidate -> candidate == session);
    }

    private static void pruneIfEmpty(UUID playerId, ArrayDeque<RaidCaptureSession> queue) {
        if (queue.isEmpty()) {
            SESSIONS.remove(playerId, queue);
            LOCKS.remove(playerId);
            LAST_RETRY_ATTEMPT.remove(playerId);
        }
    }
}
