package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves a capture session survives a save/load round trip exactly, the same way
 * {@code TrophyLedgerRoundTripTest} does for trophy entries.
 */
class RaidCaptureSessionStoreRoundTripTest {

    private static final ResourceLocation GARCHOMP = ResourceLocation.parse("cobblemon:garchomp");
    private static final ResourceLocation DEFINITION = ResourceLocation.parse("cobbleraids:test_raid");

    private static RaidCaptureSessionStore roundTrip(Map<UUID, ArrayDeque<RaidCaptureSession>> live) {
        RaidCaptureSessionStore store = new RaidCaptureSessionStore();
        store.update(live);
        CompoundTag tag = store.save(new CompoundTag(), null);
        return RaidCaptureSessionStore.load(tag, null);
    }

    @Test
    @DisplayName("an AWAITING_CLAIM session survives a save/load round trip exactly")
    void awaitingClaimSessionRoundTrips() {
        UUID playerId = UUID.randomUUID();
        UUID raidId = UUID.randomUUID();
        RaidCaptureSession original = RaidCaptureSession.offer(playerId, raidId, DEFINITION,
                RaidRarityTier.LEGENDARY, GARCHOMP, "mega_x", 75, true);

        ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
        queue.addLast(original);
        RaidCaptureSessionStore reloaded = roundTrip(Map.of(playerId, queue));

        RaidCaptureSession result = reloaded.take().get(playerId).peekFirst();
        assertEquals(original.playerId(), result.playerId());
        assertEquals(original.raidId(), result.raidId());
        assertEquals(original.definitionId(), result.definitionId());
        assertEquals(original.tier(), result.tier());
        assertEquals(original.species(), result.species());
        assertEquals(original.form(), result.form());
        assertEquals(original.level(), result.level());
        assertEquals(original.shiny(), result.shiny());
        assertEquals(original.phase(), result.phase());
        assertEquals(0, result.bankedRaidPoints());
        assertNull(result.pokemonNbt());
    }

    @Test
    @DisplayName("a claimed, AWAITING_CHOICE session's banked points and deadline survive exactly")
    void claimedSessionRoundTrips() {
        UUID playerId = UUID.randomUUID();
        UUID raidId = UUID.randomUUID();
        RaidCaptureSession original = RaidCaptureSession.offer(playerId, raidId, DEFINITION,
                        RaidRarityTier.LEGENDARY, GARCHOMP, null, 75, true)
                .withClaimed(76, 1_000_000L, 60);

        ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
        queue.addLast(original);
        RaidCaptureSession result = roundTrip(Map.of(playerId, queue)).take().get(playerId).peekFirst();

        assertEquals(RaidCaptureSession.CapturePhase.AWAITING_CHOICE, result.phase());
        assertEquals(76, result.bankedRaidPoints());
        assertEquals(1_000_000L + 60_000L, result.choiceDeadlineEpochMs());
    }

    @Test
    @DisplayName("a resolved session with banked points and a stored NBT blob survives exactly")
    void resolvedSessionWithBankedPointsAndNbtRoundTrips() {
        UUID playerId = UUID.randomUUID();
        UUID raidId = UUID.randomUUID();
        CompoundTag nbt = new CompoundTag();
        nbt.putString("marker", "rolled-pokemon");

        RaidCaptureSession original = RaidCaptureSession.offer(playerId, raidId, DEFINITION,
                        RaidRarityTier.MYTHICAL, GARCHOMP, null, 100, false)
                .withClaimed(110, 0L, 60)
                .withAttemptStarted(1_000L, 18)
                .withPulseScored(1.0, 2_000L).withPulseScored(0.5, 3_000L).withPulseScored(0.0, 4_000L)
                .withThrowResolved(1.0, true, 0.04)
                .withPokemonNbt(nbt)
                .withDelivery(RaidCaptureSession.DeliveryState.PENDING_RETRY);

        ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
        queue.addLast(original);
        RaidCaptureSession result = roundTrip(Map.of(playerId, queue)).take().get(playerId).peekFirst();

        assertEquals(RaidCaptureSession.CapturePhase.RESOLVED, result.phase());
        assertTrue(result.rolled());
        assertTrue(result.rollSucceeded());
        assertEquals(0.04, result.finalChanceUsed(), 0.0);
        assertEquals(1.0, result.pulse1Score(), 0.0);
        assertEquals(0.5, result.pulse2Score(), 0.0);
        assertEquals(0.0, result.pulse3Score(), 0.0);
        assertEquals(RaidCaptureSession.DeliveryState.PENDING_RETRY, result.delivery());
        assertEquals(nbt, result.pokemonNbt());
        assertEquals(110, result.bankedRaidPoints());
    }

    @Test
    @DisplayName("an untouched player has no sessions at all, not an empty queue")
    void unknownPlayerHasNoSessions() {
        RaidCaptureSessionStore reloaded = roundTrip(Map.of());

        assertNull(reloaded.take().get(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a tier name the game no longer has drops that one entry, not the whole load")
    void unknownTierIsDroppedNotFatal() {
        UUID playerId = UUID.randomUUID();
        ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
        queue.addLast(RaidCaptureSession.offer(playerId, UUID.randomUUID(), DEFINITION,
                RaidRarityTier.STARTER, GARCHOMP, null, 20, false));
        RaidCaptureSessionStore store = new RaidCaptureSessionStore();
        store.update(Map.of(playerId, queue));
        CompoundTag tag = store.save(new CompoundTag(), null);
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        CompoundTag session = players.getCompound(0).getList("queue", Tag.TAG_COMPOUND).getCompound(0);
        session.putString("tier", "retired_tier_from_an_old_version");

        assertNull(RaidCaptureSessionStore.load(tag, null).take().get(playerId),
                "the one corrupted session must not survive the load");
    }

    @Test
    @DisplayName("multiple queued sessions for one player keep their order")
    void multipleSessionsStayOrdered() {
        UUID playerId = UUID.randomUUID();
        RaidCaptureSession first = RaidCaptureSession.offer(playerId, UUID.randomUUID(), DEFINITION,
                RaidRarityTier.STARTER, GARCHOMP, null, 20, false);
        RaidCaptureSession second = RaidCaptureSession.offer(playerId, UUID.randomUUID(), DEFINITION,
                RaidRarityTier.MYTHICAL, GARCHOMP, null, 100, true);
        ArrayDeque<RaidCaptureSession> queue = new ArrayDeque<>();
        queue.addLast(first);
        queue.addLast(second);

        ArrayDeque<RaidCaptureSession> result = roundTrip(Map.of(playerId, queue)).take().get(playerId);

        assertEquals(2, result.size());
        assertEquals(first.raidId(), result.peekFirst().raidId());
        assertEquals(second.raidId(), result.peekLast().raidId());
    }
}
