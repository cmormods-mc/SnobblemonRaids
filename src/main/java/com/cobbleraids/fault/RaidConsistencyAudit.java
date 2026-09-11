package com.cobbleraids.fault;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.lifecycle.RaidLifecycleCoordinator;
import com.cobbleraids.lifecycle.RaidRewardService;
import com.cobbleraids.lobby.RaidLobby;
import com.cobbleraids.lobby.RaidLobbyManager;
import com.cobbleraids.presentation.RaidBossGlowService;
import com.cobbleraids.raid.RaidRegistry;
import com.cobbleraids.raid.RaidSession;
import com.cobbleraids.reward.PendingRaidReward;
import com.cobbleraids.spawn.RaidBossEntityMarker;
import com.cobbleraids.spawn.RaidBossLookup;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Map;
import java.util.UUID;

/**
 * Checks that this mod's subsystems still agree with each other, and says so when they do not.
 *
 * <p>Every serious bug this mod has shipped was a disagreement between two pieces of state that
 * nothing was comparing: a raid slot still held for a boss that had been destroyed, a session left
 * in the registry after finalization failed, a scoreboard team still listing bosses that no longer
 * exist. Each was found by a player noticing something odd, days later. None was visible in a log,
 * because nothing had thrown -- the state was simply wrong.
 *
 * <p>That gap widened when the fault barriers went in. A contained failure keeps the server alive,
 * which is the point, but it also means the damage it leaves behind is now the only evidence it
 * happened. So this walks the cross-subsystem invariants on a schedule and reports what does not
 * hold, turning "someone eventually notices" into a log line with an id in it.
 *
 * <p>It deliberately does not repair anything. Self-healing would hide exactly the bugs this exists
 * to surface, and a leaked slot or a stranded session is cheap for an operator to clear once they
 * know it is there. Report, do not fix.
 *
 * <p>Runs on the server thread, and each check is guarded independently: an audit that throws would
 * be a fault-detection system causing faults.
 */
public final class RaidConsistencyAudit {

    private RaidConsistencyAudit() {}

    public static RaidAuditReport run(MinecraftServer server) {
        RaidAuditReport report = new RaidAuditReport();
        if (server == null) return report;

        RaidFaultBarrier.guard("audit:sessions", () -> auditSessions(server, report));
        RaidFaultBarrier.guard("audit:lobbies", () -> auditLobbies(report));
        RaidFaultBarrier.guard("audit:spawn-slots", () -> auditSpawnSlots(server, report));
        RaidFaultBarrier.guard("audit:glow", () -> auditGlowTeams(server, report));
        RaidFaultBarrier.guard("audit:rewards", () -> auditPendingRewards(report));
        RaidFaultBarrier.guard("audit:finalization", () -> auditFinalization(report));
        RaidFaultBarrier.guard("audit:threading", () -> auditThreading(report));
        RaidFaultBarrier.guard("audit:reward-policy", () -> auditRewardPolicy(report));
        return report;
    }

    /**
     * A session still in the registry after reaching a terminal state means finalization's cleanup
     * did not run. This is the exact shape of the bug the fault barriers created: the failure was
     * contained, the server lived, and the raid was stranded with its boss and slot held.
     */
    private static void auditSessions(MinecraftServer server, RaidAuditReport report) {
        for (RaidSession raid : RaidRegistry.all()) {
            report.checked();
            RaidSession.Status status = raid.getStatus();
            if (status == RaidSession.Status.COMPLETED
                    || status == RaidSession.Status.FAILED
                    || status == RaidSession.Status.ABORTED) {
                report.error("stranded-session", "raid " + raid.getId() + " (" + raid.getDefinitionId()
                        + ") is " + status + " but still registered; its cleanup did not run");
            }

            PokemonEntity boss = raid.getBossEntity();
            // Only warned about, not errored: a boss reports itself removed while its chunk is merely
            // unloaded, and although an active raid normally keeps that chunk loaded through its
            // players, a disconnect at the wrong moment can leave it briefly true and harmless.
            if (status == RaidSession.Status.ACTIVE && (boss == null || boss.isRemoved())) {
                report.warn("active-raid-without-boss",
                        "raid " + raid.getId() + " is ACTIVE but its boss entity is gone");
            }
        }
    }

    /**
     * A definition still naming its own rewards ignores the server-wide policy.
     *
     * <p>Warned about rather than errored, because it is a legitimate thing to do on purpose -- a
     * hand-written definition is meant to keep its own loot. What it must not be is an accident,
     * and an unfinished migration looks exactly like a deliberate choice from the outside: the raid
     * works, nothing throws, and the boss keeps handing out whatever it always did. Naming the
     * definition is the whole point; the operator decides whether it belongs there.
     */
    private static void auditRewardPolicy(RaidAuditReport report) {
        for (RaidDefinition definition : RaidDefinitionRegistry.all()) {
            report.checked();
            if (RaidDefinitionRegistry.isSelfDescribing(definition)) {
                report.warn("reward-policy-legacy", "raid definition " + definition.id()
                        + " names rewards of its own, so the reward policy does not apply to it");
            }
        }
    }

    private static void auditLobbies(RaidAuditReport report) {
        for (RaidLobby lobby : RaidLobbyManager.all()) {
            report.checked();
            PokemonEntity boss = lobby.boss();
            if (boss == null || boss.isRemoved()) {
                report.warn("lobby-without-boss",
                        "lobby " + lobby.id() + " is recruiting for a boss that no longer exists");
                continue;
            }
            if (!RaidBossEntityMarker.isRaidBoss(boss)) {
                report.warn("lobby-for-non-boss",
                        "lobby " + lobby.id() + " is recruiting for an entity that is not a raid boss");
            }
        }
    }

    /**
     * A tracked spawn whose boss resolves and reports removed is a raid slot held for something that
     * no longer exists -- the 0.8.33 regression. An entry that does not resolve is skipped, because
     * an unloaded chunk is not a missing boss.
     */
    private static void auditSpawnSlots(MinecraftServer server, RaidAuditReport report) {
        for (Map.Entry<UUID, ResourceLocation> entry : RaidSpawnScheduler.trackedBosses().entrySet()) {
            report.checked();
            PokemonEntity boss = RaidBossLookup.resolve(server, entry.getKey(), entry.getValue());
            if (boss == null) continue;
            if (boss.isRemoved()) {
                report.error("leaked-raid-slot", "natural raid slot still held for destroyed boss "
                        + entry.getKey() + " in " + entry.getValue());
            } else if (!RaidBossEntityMarker.isRaidBoss(boss)) {
                report.warn("tracked-non-boss",
                        "a raid slot is held by " + entry.getKey() + ", which is no longer a raid boss");
            }
        }
    }

    /**
     * Scoreboard team membership is saved into the world and replayed to every joining client, and an
     * entity's scoreboard name is its UUID -- so a boss left in a glow team after it is gone is
     * permanent growth in the world save. This compares the teams against what is actually tracked.
     */
    private static void auditGlowTeams(MinecraftServer server, RaidAuditReport report) {
        Map<UUID, ResourceLocation> tracked = RaidBossGlowService.trackedBosses();
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            PlayerTeam team = server.getScoreboard().getPlayerTeam(RaidBossGlowService.teamName(tier));
            if (team == null) continue;
            for (String member : team.getPlayers()) {
                report.checked();
                UUID memberId = parseUuid(member);
                if (memberId == null || !tracked.containsKey(memberId)) {
                    report.warn("orphaned-glow-team-member", "scoreboard team " + team.getName()
                            + " still lists " + member + ", which is not a tracked raid boss");
                }
            }
        }
    }

    private static void auditPendingRewards(RaidAuditReport report) {
        for (UUID playerId : RaidRewardService.playersWithPending()) {
            for (PendingRaidReward pending : RaidRewardService.pendingFor(playerId)) {
                report.checked();
                if (RaidDefinitionRegistry.get(pending.definitionId()) == null) {
                    report.warn("reward-for-unknown-definition", "player " + playerId
                            + " holds a reward for " + pending.definitionId() + ", which is not loaded");
                }
            }
        }
    }

    /**
     * Finalization claims are held only for the duration of one call, and this runs on the same
     * thread between ticks -- so anything still claimed here outlived its call, which is precisely
     * the leak that used to strand a raid permanently.
     */
    private static void auditFinalization(RaidAuditReport report) {
        report.checked();
        int inFlight = RaidLifecycleCoordinator.finalizationsInFlight();
        if (inFlight > 0) {
            report.error("leaked-finalization-claim", inFlight
                    + " raid(s) still claimed for finalization outside any finalization call");
        }
    }



    /**
     * Whether anything has touched world state off the server thread since startup.
     *
     * <p>Reported long after the fact on purpose: the offending call already returned, and its log
     * line may be hours old or rotated away, but the consequence -- a Pokemon's health or a packet
     * written from an arbitrary thread -- is the kind of corruption that surfaces much later.
     */
    private static void auditThreading(RaidAuditReport report) {
        report.checked();
        if (!RaidThreadGuard.isKnown()) return;
        for (Map.Entry<String, Integer> entry : RaidThreadGuard.offThreadObservations().entrySet()) {
            report.error("off-server-thread", entry.getKey() + " has run off the server thread "
                    + entry.getValue() + " time(s) since startup; entity and packet writes on that"
                    + " path are not thread safe");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // A player name rather than an entity UUID: somebody put a person in the team by hand.
            return null;
        }
    }

    /** Logs a report, worst findings first, or stays silent when everything holds. */
    public static void log(RaidAuditReport report) {
        if (report.isClean()) return;
        if (report.hasErrors()) {
            RaidLog.error("Consistency audit: " + report.summary());
        } else {
            RaidLog.warn("Consistency audit: " + report.summary());
        }
        for (String line : report.lines()) RaidLog.warn("  " + line);
    }
}
