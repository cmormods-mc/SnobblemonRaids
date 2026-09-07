package com.cobbleraids.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.cobbleraids.spawn.RaidSpawnScheduler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

/** Operator-facing command tree. Implementation is split into small testable command services. */
public final class RaidAdminCommand {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    private RaidAdminCommand() {}

    /**
     * Every operator subcommand carries the permission check itself, rather than relying on the one
     * on the shared "cobbleraids" root.
     *
     * RaidRewardCommand registers its own "cobbleraids" root for the player-facing reward commands,
     * and it registers first. Brigadier's CommandNode.addChild merges a same-named node by keeping
     * the EXISTING node -- requirement and all -- and copying only the new node's children onto it,
     * so the root requirement declared below was being discarded outright at merge time. That left
     * every operator command, spawn/despawn/reload/reward grant included, executable by any player.
     * Putting the check on each subcommand keeps it attached to nodes that actually survive the merge.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleraids")
                        .requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))
                        .then(admin("list")
                                .executes(ctx -> RaidAdminSpawnOps.list(ctx.getSource()))
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .suggests(RaidSuggestions.TIERS)
                                        .executes(ctx -> RaidAdminSpawnOps.listTier(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "tier")))))
                        .then(admin("spawn")
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        .suggests(RaidSuggestions.SPECIES)
                                        .executes(ctx -> RaidAdminSpawnOps.spawnNearPlayer(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> RaidAdminSpawnOps.spawnAt(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "pokemon"),
                                                        Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(admin("spawninfo")
                                .executes(ctx -> RaidSpawnScheduler.sendSpawnInfo(ctx.getSource())))
                        .then(admin("testwild")
                                .then(Commands.argument("pokemon", StringArgumentType.word())
                                        .suggests(RaidSuggestions.SPECIES)
                                        .executes(ctx -> RaidSpawnScheduler.testWild(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))))
                        .then(admin("despawn")
                                .executes(ctx -> RaidAdminBossOps.despawnNearest(ctx.getSource()))
                                .then(Commands.literal("all")
                                        .executes(ctx -> RaidAdminBossOps.despawnAll(ctx.getSource()))))
                        .then(admin("reload")
                                .executes(ctx -> RaidAdminConfigOps.reload(ctx.getSource())))
                        .then(admin("cooldown")
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("definition", ResourceLocationArgument.id())
                                                .suggests(RaidSuggestions.DEFINITIONS)
                                                .executes(ctx -> RaidAdminSpawnOps.resetCooldown(
                                                        ctx.getSource(), ResourceLocationArgument.getId(ctx, "definition"))))))
                        // "reward" itself is shared with the player-facing RaidRewardCommand, so only
                        // "grant" underneath it is operator-only.
                        .then(Commands.literal("reward")
                                .then(admin("grant")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("definition", ResourceLocationArgument.id())
                                                        .suggests(RaidSuggestions.DEFINITIONS)
                                                        .executes(ctx -> RaidAdminRewardOps.grant(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "target"),
                                                                ResourceLocationArgument.getId(ctx, "definition")))))))
                        .then(admin("debug")
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
