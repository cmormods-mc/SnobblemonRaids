package com.cobbleraids.lifecycle;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.reward.ContributionMath;
import com.cobbleraids.reward.PendingRaidReward;
import com.cobbleraids.reward.PendingRewardStore;
import com.cobbleraids.reward.NativeRewardScreenGateway;
import com.cobbleraids.reward.RaidRewardGrantEngine;
import com.cobbleraids.config.RaidRewardPolicyManager;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.reward.RewardGuiBackends;
import com.cobbleraids.reward.plan.RewardPlanResolver;
import com.cobbleraids.reward.RewardGrantResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Server-authoritative reward queue. SkiesGUIs is presentation only: all claims are validated here,
 * consumed exactly once, and then granted from the raid definition snapshot.
 */
public final class RaidRewardService {
    private static final Map<UUID, ArrayDeque<PendingRaidReward>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> OPEN_DELAY = new ConcurrentHashMap<>();
    /** Per-player claim monitors; see {@link #lockFor}. */
    private static final Map<UUID, Object> CLAIM_LOCKS = new ConcurrentHashMap<>();
    private static final int GUI_OPEN_DELAY_TICKS = 2;
    /**
     * Longer than the post-victory delay on purpose. At victory the player is already in the world
     * and we are only waiting for the battle-end screen packet; on join the client is still
     * finishing its terrain load, and a screen pushed into that window is drawn behind the loading
     * overlay and dismissed with it -- which looks exactly like the reward silently not appearing.
     */
    private static final int JOIN_OPEN_DELAY_TICKS = 40;
    private RaidRewardService() {}

    /**
     * Restores unclaimed rewards saved by the previous session. Before this existed the queue was
     * memory-only, so a restart silently discarded every reward nobody had claimed yet.
     */
    public static void onServerStarted(MinecraftServer server) {
        PENDING.clear();
        OPEN_DELAY.clear();
        PENDING.putAll(PendingRewardStore.get(server).take());
        int claims = PENDING.values().stream().mapToInt(ArrayDeque::size).sum();
        if (claims > 0) {
            RaidLog.info("Restored " + claims + " unclaimed raid reward(s) for "
                    + PENDING.size() + " player(s).");
        }
    }

    /**
     * Re-offers an unclaimed reward when its owner logs in.
     *
     * <p>{@link #grant} only queues a screen-open for participants who are online at the moment of
     * victory, so a reward that outlived a disconnect or a server restart had nothing left to
     * present it: the queue was restored correctly and the player was simply never told, leaving
     * "/cobbleraids reward claim all" -- the raw claim the GUI button itself runs -- as the only way
     * to collect it, which grants the items with no reveal screen at all.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (player != null && hasPending(player.getUUID())) {
            OPEN_DELAY.put(player.getUUID(), JOIN_OPEN_DELAY_TICKS);
        }
    }

    /**
     * Writes the queue back to disk. Called after every mutation rather than on a timer: claims are
     * rare (once per player per raid), so this is far cheaper than it looks, and it means a crash
     * cannot lose a reward that the player has already been told they have.
     */
    private static void persist(MinecraftServer server) {
        if (server != null) PendingRewardStore.get(server).update(PENDING);
    }

    /**
     * Persists the queue and writes it to disk now, rather than at the next world save.
     *
     * <p>Used when a claim is consumed, and the reason is a duplication window. {@link #persist}
     * only marks the SavedData dirty; Minecraft flushes it on the autosave, minutes away. A
     * player's inventory, however, reaches disk the moment they log out. So: claim, receive the
     * items, log out -- inventory written -- then a hard crash before the next autosave, and the
     * server comes back with the claim still queued and the player still holding the items. One
     * reward, twice.
     *
     * <p>Flushing here makes the claim durable before the items can be. The reverse window it
     * opens -- crash between this write and the grant a few microseconds later, losing one reward
     * -- is smaller by minutes, and on a server with an economy losing a reward is a complaint
     * while duplicating one is an exploit.
     *
     * <p>Cheap enough to do unconditionally: a claim happens once per player per raid, not per
     * tick, and save() writes only what is dirty.
     */
    private static void persistNow(MinecraftServer server) {
        if (server == null) return;
        persist(server);
        RaidFaultBarrier.guard("reward-claim-flush", () -> server.overworld().getDataStorage().save());
    }

    /** Admin view of one player's queue, front of queue first. */
    public static List<PendingRaidReward> pendingFor(UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(playerId);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    /** Drops every queued reward for one player. Returns how many were removed. */
    public static int clearPending(UUID playerId, MinecraftServer server) {
        ArrayDeque<PendingRaidReward> queue = PENDING.remove(playerId);
        OPEN_DELAY.remove(playerId);
        int removed = queue == null ? 0 : queue.size();
        if (removed > 0) persist(server);
        return removed;
    }

    /** Player ids that currently hold at least one unclaimed reward. */
    public static Set<UUID> playersWithPending() {
        return Set.copyOf(PENDING.keySet());
    }

    public static void grant(RaidRewardEligibility eligibility, MinecraftServer server) {
        if (eligibility == null || eligibility.outcome() != RaidOutcome.VICTORY || server == null) return;
        RaidDefinition definition = RaidDefinitionRegistry.get(eligibility.definitionId());
        if (definition == null) {
            RaidLog.error("Cannot create rewards for " + eligibility.raidId() + ": definition " + eligibility.definitionId() + " is not loaded");
            return;
        }
        RaidDefinition.Rewards rewards = definition.rewards();
        if (rewards.choices().isEmpty()) {
            RaidLog.error("Raid " + definition.id() + " has no GUI reward choices; no claim GUI queued");
            return;
        }

        Map<UUID, Double> percentages = ContributionMath.percentages(eligibility.contribution(), eligibility.participants());
        RaidDefinition.RewardChoice sampleChoice = rewards.choices().values().stream().findFirst().orElse(null);

        for (UUID playerId : eligibility.participants()) {
            double percentage = percentages.getOrDefault(playerId, 0.0);
            int bonusRolls = RewardPlanResolver.bonusRollsFor(rewards, sampleChoice,
                    RaidRewardPolicyManager.get(), percentage);
            // Fixed per claim, at victory, so an unclaimed reward regenerates rather than rerolls
            // when the server restarts. See PendingRewardStore for why the contents themselves are
            // not written to disk.
            long rewardSeed = ThreadLocalRandom.current().nextLong();
            PendingRaidReward pending = new PendingRaidReward(
                    eligibility.raidId(), eligibility.definitionId(), definition.rarityTier(), rewards, percentage, bonusRolls,
                    eligibility.elapsedCombatTicks(), eligibility.participants().size(), rewardSeed,
                    eligibility.renownTitle());
            PENDING.computeIfAbsent(playerId, ignored -> new ArrayDeque<>()).addLast(pending);
            if (server.getPlayerList().getPlayer(playerId) != null) OPEN_DELAY.putIfAbsent(playerId, GUI_OPEN_DELAY_TICKS);
        }
        persist(server);
    }

    /** Called from END_SERVER_TICK so the battle-end screen packet is processed before the reward chest opens. */
    public static void tick(MinecraftServer server) {
        // OPEN_DELAY is empty on almost every tick (only populated for ~2 ticks after a victory), so
        // skip the ConcurrentHashMap iteration entirely rather than paying for it 20x/second at idle.
        if (OPEN_DELAY.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Integer>> it = OPEN_DELAY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) openCurrent(player);
        }
    }

    public static boolean hasPending(UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(playerId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * Choice ids this player can claim right now, for command tab completion. Only the front of the
     * queue is claimable, so suggesting anything else would offer an id the claim would reject.
     */
    public static Set<String> pendingChoiceIds(UUID playerId) {
        PendingRaidReward pending = peek(playerId);
        return pending == null ? Set.of() : pending.rewards().choices().keySet();
    }

    public static boolean openCurrent(ServerPlayer player) {
        PendingRaidReward pending = peek(player.getUUID());
        if (pending == null) {
            player.sendSystemMessage(Component.literal("You do not have an unclaimed raid reward."));
            return false;
        }
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Raid contribution: %.1f%% | Contribution bonus rolls: %d",
                pending.contributionPercentage(), pending.contributionBonusRolls())));
        boolean opened = NativeRewardScreenGateway.tryOpen(player, pending)
                || RewardGuiBackends.active().open(player, pending.rewards().guiId());
        if (!opened) sendChatFallbackChoices(player, pending);
        return true;
    }

    private static void sendChatFallbackChoices(ServerPlayer player, PendingRaidReward pending) {
        player.sendSystemMessage(Component.literal("Reward GUI unavailable; choose with /cobbleraids reward claim <id>:"));
        for (String choiceId : pending.rewards().choices().keySet()) {
            player.sendSystemMessage(Component.literal(" - " + choiceId));
        }
    }

    /**
     * One lock per claiming player rather than one for the whole server.
     *
     * <p>All three claim entry points used to be {@code static synchronized}, so every player's claim
     * serialized on a single monitor -- and RaidRewardGrantEngine.grantChoice, which rolls loot and
     * inserts into an inventory, ran inside it. On a 30-player server a raid wave means thirty claims
     * queued behind one another on the server thread for no reason: they touch different queues and
     * different inventories.
     *
     * <p>The lock still has to exist per player, because a GUI click and a command can arrive for the
     * same player in the same tick and both try to spend one claim token. Interning on the player's
     * UUID gives exactly that scope. Entries are dropped when the player's queue empties, so this map
     * does not outlive the rewards it guards.
     */
    private static Object lockFor(UUID playerId) {
        return CLAIM_LOCKS.computeIfAbsent(playerId, ignored -> new Object());
    }

    public static boolean claim(ServerPlayer player, String choiceId) {
        return claimInternal(player, choiceId) != null;
    }

    /**
     * Same as {@link #claim} but returns what was actually granted instead of a bare boolean, so the
     * native reward screen's network handler can report the real result back to the client. Any
     * RuntimeException from a failed grant is already messaged to the player here; callers that don't
     * want the exception to propagate should use {@link #claimNative} instead.
     */
    static RewardGrantResult claimInternal(ServerPlayer player, String choiceId) {
        synchronized (lockFor(player.getUUID())) {
            return claimLocked(player, choiceId);
        }
    }

    private static RewardGrantResult claimLocked(ServerPlayer player, String choiceId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(player.getUUID());
        PendingRaidReward pending = queue == null ? null : queue.peekFirst();
        if (pending == null) {
            player.sendSystemMessage(Component.literal("You do not have an unclaimed raid reward."));
            return null;
        }
        RaidDefinition.RewardChoice choice = pending.rewards().choices().get(choiceId);
        if (choice == null) {
            player.sendSystemMessage(Component.literal("That reward choice is not valid for this raid."));
            return null;
        }

        // Consume before granting so duplicate GUI/command clicks cannot double-spend the claim token.
        queue.removeFirst();
        if (queue.isEmpty()) {
            PENDING.remove(player.getUUID());
            // Safe to drop while holding it: a later claim by this player simply interns a new
            // monitor, and there is no longer a queue for two callers to race over.
            CLAIM_LOCKS.remove(player.getUUID());
        }
        persistNow(player.getServer());
        try {
            RewardGrantResult result = RaidRewardGrantEngine.grantChoice(player, pending, choice);
            if (CobbleRaidsConfigManager.get().debugLogging()) {
                RaidLog.info("" + player.getGameProfile().getName() + " claimed '" + choiceId
                        + "' for raid " + pending.definitionId()
                        + String.format(Locale.ROOT, " (contribution %.1f%%, %d bonus roll(s))",
                                pending.contributionPercentage(), pending.contributionBonusRolls())
                        + ": base=" + summarize(result.baseItems())
                        + " chance=" + summarize(result.chanceItemsGranted())
                        + " bonus=" + summarize(result.contributionBonusItems())
                        + " currency=" + result.currencyGranted()
                        + " points=" + result.raidPointsGranted());
            }
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Raid reward claimed. Contribution %.1f%% awarded %d bonus roll%s. Granted: %s%s",
                    pending.contributionPercentage(), pending.contributionBonusRolls(),
                    pending.contributionBonusRolls() == 1 ? "" : "s", describeAll(result),
                    // Left out entirely rather than shown as zero: with nothing configured there
                    // is nothing to tell the player about.
                    (result.paidPoints() ? " (+" + result.raidPointsGranted() + " RP)" : "")
                            + (result.paidCurrency() ? " (+" + result.currencyGranted() + " CobbleDollars)" : ""))));
            if (hasPending(player.getUUID())) OPEN_DELAY.put(player.getUUID(), GUI_OPEN_DELAY_TICKS);
            return result;
        } catch (RuntimeException ex) {
            // Restore the exact claim at the front if granting fails before completion.
            PENDING.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>()).addFirst(pending);
            persistNow(player.getServer());
            player.sendSystemMessage(Component.literal("Raid reward grant failed; your claim was preserved. Contact an administrator."));
            throw ex;
        }
    }

    /** Used by the native reward screen's network handler: never throws, since the player is already messaged. */
    public static RewardGrantResult claimNative(ServerPlayer player, String choiceId) {
        try {
            return claimInternal(player, choiceId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static PendingRaidReward peek(UUID playerId) {
        ArrayDeque<PendingRaidReward> queue = PENDING.get(playerId);
        return queue == null ? null : queue.peekFirst();
    }

    private static String summarize(List<RaidDefinition.RewardItem> items) {
        if (items.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (RaidDefinition.RewardItem item : items) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(item.item()).append(" x").append(item.amount());
        }
        return builder.toString();
    }

    /**
     * Bound to SERVER_STOPPED rather than SERVER_STOPPING on purpose: the queue is mirrored into
     * PendingRewardStore, a SavedData written during the world save that SERVER_STOPPING still
     * precedes. Clearing the live map only once every world is closed keeps unclaimed rewards a
     * disk concern and this map a memory one. onServerStarted refills it from disk either way.
     */
    public static void onServerStopped() {
        PENDING.clear();
        OPEN_DELAY.clear();
        CLAIM_LOCKS.clear();
    }

    private static String describeAll(RewardGrantResult result) {
        List<RaidDefinition.RewardItem> all = result.allGranted();
        if (all.isEmpty()) return "nothing";
        StringBuilder builder = new StringBuilder();
        for (RaidDefinition.RewardItem item : all) {
            if (builder.length() > 0) builder.append(", ");
            Item resolved = BuiltInRegistries.ITEM.get(item.item());
            builder.append(new ItemStack(resolved).getHoverName().getString()).append(" x").append(item.amount());
        }
        return builder.toString();
    }
}
