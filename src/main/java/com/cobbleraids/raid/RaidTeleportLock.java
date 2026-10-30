package com.cobbleraids.raid;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Whether a player is fighting a raid and so may not be moved by a teleport.
 *
 * <p>The raid has no distance check once it starts (Cobblemon's own flee scan is switched off for
 * raids), so a warp to a healer and a /back used to leave a player in a fight they were nowhere
 * near. Refusing the teleport closes that for every route at once. {@code /cobbleraids leave}
 * withdraws the player from the raid, which lifts the lock, so nobody is ever stuck.
 *
 * <p>An owned encounter is excluded: its owner moves its players between rooms itself.
 */
public final class RaidTeleportLock {
    private RaidTeleportLock() {}

    public static boolean isLocked(ServerPlayer player) {
        // Empty on almost every call; teleports are rare but this keeps the idle cost at one read.
        if (RaidRegistry.isEmpty()) return false;
        UUID id = player.getUUID();
        for (RaidSession raid : RaidRegistry.all()) {
            if (locks(raid.getStatus(), raid.isOwned(), raid.isActiveParticipant(id))) return true;
        }
        return false;
    }

    static boolean locks(RaidSession.Status status, boolean owned, boolean activeParticipant) {
        return activeParticipant && !owned
                && (status == RaidSession.Status.ACTIVE || status == RaidSession.Status.WAITING);
    }
}
