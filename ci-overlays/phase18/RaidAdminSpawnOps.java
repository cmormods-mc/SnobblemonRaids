package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class RaidAdminSpawnOps {
    private RaidAdminSpawnOps() {}

    static int list(CommandSourceStack source) {
        List<RaidDefinition> definitions = RaidDefinitionRegistry.all().stream()
                .sorted(Comparator.comparing(definition -> definition.id().toString())).toList();
        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No CobbleRaids definitions are currently loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Loaded CobbleRaids definitions (" + definitions.size() + "):")
                .withStyle(ChatFormatting.GOLD), false);
        for (RaidDefinition definition : definitions) {
            source.sendSuccess(() -> Component.literal(" - " + definition.id() + " | " + definition.species()
                    + " Lv." + definition.level() + " | natural=" + definition.spawn().enabled()
                    + " | maxPlayers=" + definition.recruitment().maxPlayers()), false);
        }
        return definitions.size();
    }

    static int spawnNearPlayer(CommandSourceStack source, String rawId) {
        ServerPlayer player;
        try { player = source.getPlayerOrException(); }
        catch (Exception ex) {
            source.sendFailure(Component.literal("This form requires a player. From console, provide explicit coordinates."));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        return spawnAt(source, rawId, player.position().add(look.x * 3.0, 0.0, look.z * 3.0));
    }

    static int spawnAt(CommandSourceStack source, String rawId, Vec3 position) {
        ResourceLocation id;
        try {
            id = rawId.contains(":") ? ResourceLocation.parse(rawId)
                    : ResourceLocation.fromNamespaceAndPath("cobbleraids", rawId);
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Invalid raid id: " + rawId));
            return 0;
        }
        RaidDefinition definition = RaidDefinitionRegistry.get(id);
        if (definition == null) {
            source.sendFailure(Component.literal("Unknown raid definition: " + id + ". Use /cobbleraids list."));
            return 0;
        }
        try {
            PokemonEntity boss = RaidBossSpawner.spawnAt(source.getLevel(), position, definition);
            source.sendSuccess(() -> Component.literal("Spawned raid boss " + id + " at " + format(boss.position())
                    + " in " + source.getLevel().dimension().location()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to spawn " + id + ": " + ex.getMessage()));
            return 0;
        }
    }

    private static String format(Vec3 position) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", position.x, position.y, position.z);
    }
}
