package com.cobbleraids.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

/** Operator-facing command tree. Implementation is split into small testable command services. */
public final class RaidAdminCommand {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    private RaidAdminCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .then(Commands.literal("list")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .executes(ctx -> RaidAdminSpawnOps.list(ctx.getSource())))
                        .then(Commands.literal("spawn")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .then(Commands.argument("raid_id", StringArgumentType.word())
                                        .executes(ctx -> RaidAdminSpawnOps.spawnNearPlayer(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "raid_id")))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> RaidAdminSpawnOps.spawnAt(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "raid_id"),
                                                        Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("despawn")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .executes(ctx -> RaidAdminBossOps.despawnNearest(ctx.getSource()))
                                .then(Commands.literal("all")
                                        .executes(ctx -> RaidAdminBossOps.despawnAll(ctx.getSource()))))
                        .then(Commands.literal("debug")
                                .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                                .then(Commands.literal("status")
                                        .executes(ctx -> RaidAdminDebugOps.status(ctx.getSource())))
                                .then(Commands.literal("raids")
                                        .executes(ctx -> RaidAdminDebugOps.raids(ctx.getSource()))))
        ));
    }
}
