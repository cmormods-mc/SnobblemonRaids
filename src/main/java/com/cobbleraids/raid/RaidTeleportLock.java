package com.cobbleraids.raid;

import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;

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

    /** Tells the player why, and answers true: the caller cancels the teleport when this is true. */
    public static boolean refuse(ServerPlayer player) {
        if (!isLocked(player)) return false;
        player.sendSystemMessage(Component.literal("You cannot teleport during a raid. Use /cobbleraids leave to withdraw first.")
                .withStyle(ChatFormatting.RED), true);
        return true;
    }

    /**
     * Whether a packet-level teleport is vanilla correcting the player's position rather than moving
     * them. Rejected or too-fast movement is answered with a teleport to where the server already
     * has the player, so blocking those would break movement checks. A warp has to go somewhere else,
     * so a destination equal to the current position is never a warp.
     */
    public static boolean isInPlace(ServerPlayer player, double x, double y, double z, Set<RelativeMovement> relative) {
        return isInPlace(player.getX(), player.getY(), player.getZ(), x, y, z,
                relative.contains(RelativeMovement.X), relative.contains(RelativeMovement.Y), relative.contains(RelativeMovement.Z));
    }

    static boolean isInPlace(double px, double py, double pz, double x, double y, double z,
                             boolean relX, boolean relY, boolean relZ) {
        return near(px, relX ? px + x : x) && near(py, relY ? py + y : y) && near(pz, relZ ? pz + z : z);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0E-3;
    }
}
