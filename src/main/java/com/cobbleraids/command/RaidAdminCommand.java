package com.cobbleraids.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

/** Operator-facing command tree. Implementation is split into small testable command services. */
public final class RaidAdminCommand {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    private RaidAdminCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                        .then(Commands.literal("list")
                                .executes(ctx -> RaidAdminSpawnOps.list(ctx.getSource())))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        .executes(ctx -> RaidAdminSpawnOps.spawnNearPlayer(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> RaidAdminSpawnOps.spawnAt(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pokemon"),
                                                        Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("spawninfo")
                                .executes(ctx -> RaidSpawnScheduler.sendSpawnInfo(ctx.getSource())))
                        .then(Commands.literal("testwild")
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        .executes(ctx -> RaidSpawnScheduler.testWild(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))))
                        .then(Commands.literal("despawn")
                                .executes(ctx -> RaidAdminBossOps.despawnNearest(ctx.getSource()))
                                .then(Commands.literal("all")
                                        .executes(ctx -> RaidAdminBossOps.despawnAll(ctx.getSource()))))
                        .then(Commands.literal("reload")
                                .executes(ctx -> RaidAdminConfigOps.reload(ctx.getSource())))
                        .then(Commands.literal("cooldown")
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("definition", ResourceLocationArgument.id())
                                                .executes(ctx -> RaidAdminSpawnOps.resetCooldown(
                                                        ctx.getSource(), ResourceLocationArgument.getId(ctx, "definition"))))))
                        .then(Commands.literal("reward")
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("definition", ResourceLocationArgument.id())
                                                        .executes(ctx -> RaidAdminRewardOps.grant(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "target"),
                                                                ResourceLocationArgument.getId(ctx, "definition")))))))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("status")
                                        .executes(ctx -> RaidAdminDebugOps.status(ctx.getSource())))
                                .then(Commands.literal("raids")
                                        .executes(ctx -> RaidAdminDebugOps.raids(ctx.getSource())))
                                .then(Commands.literal("history")
                                        .executes(ctx -> RaidAdminDebugOps.history(ctx.getSource())))
                                .then(Commands.literal("config")
                                        .executes(ctx -> RaidAdminDebugOps.config(ctx.getSource()))))
        ));
    }
}
