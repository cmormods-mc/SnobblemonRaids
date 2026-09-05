package com.cobbleraids.command;

import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidDefinitionRegistry;
import com.cobbleraids.spawn.RaidBossSpawner;
import com.cobbleraids.spawn.RaidSpawnScheduler;
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
            source.sendSuccess(() -> Component.literal(" - " + definition.species().getPath() + " | definition=" + definition.id()
                    + " | Lv." + definition.level() + " | natural=" + definition.spawn().enabled()
                    + " | tier=" + definition.rarityTier().serializedName()
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

    static int spawnAt(CommandSourceStack source, String rawPokemonName, Vec3 position) {
        String pokemonName = rawPokemonName.trim().toLowerCase(Locale.ROOT);
        if (pokemonName.isEmpty() || pokemonName.contains(":")) {
            source.sendFailure(Component.literal("Use the Cobblemon species name only, for example: /cobbleraids spawn garchomp"));
            return 0;
        }

        List<RaidDefinition> matches = RaidDefinitionRegistry.all().stream()
                .filter(definition -> definition.species().getNamespace().equals("cobblemon"))
                .filter(definition -> definition.species().getPath().equalsIgnoreCase(pokemonName))
                .sorted(Comparator.comparing(definition -> definition.id().toString()))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No loaded CobbleRaids definition uses Cobblemon species '"
                    + pokemonName + "'. Use /cobbleraids list."));
            return 0;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(definition -> definition.id().toString())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            source.sendFailure(Component.literal("Multiple raid definitions use '" + pokemonName
                    + "': " + ids + ". Keep one definition per species for the simple spawn command."));
            return 0;
        }

        RaidDefinition definition = matches.getFirst();
        try {
            PokemonEntity boss = RaidBossSpawner.spawnAt(source.getLevel(), position, definition);
            source.sendSuccess(() -> Component.literal("Spawned " + pokemonName + " raid (" + definition.id() + ") at "
                    + format(boss.position()) + " in " + source.getLevel().dimension().location())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Failed to spawn " + pokemonName + ": " + ex.getMessage()));
            return 0;
        }
    }

    private static String format(Vec3 position) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", position.x, position.y, position.z);
    }

    static int resetCooldown(CommandSourceStack source, ResourceLocation definitionId) {
        if (RaidDefinitionRegistry.get(definitionId) == null) {
            source.sendFailure(Component.literal("No loaded raid definition '" + definitionId + "'. Use /cobbleraids list."));
            return 0;
        }
        boolean wasOnCooldown = RaidSpawnScheduler.resetCooldown(definitionId);
        source.sendSuccess(() -> Component.literal(wasOnCooldown
                ? "Cleared natural-spawn cooldown for " + definitionId + "."
                : definitionId + " was not on cooldown.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
