package com.cobbleraids.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who can run what, proven against the real command tree rather than read off the source.
 *
 * <p>The tree is built by {@link RaidCommands#buildAll}, the same call the server makes, so this
 * cannot pass while the live registration is different. It matters because the shared
 * {@code cobbleraids} root is merged by Brigadier in a way that silently discards a requirement
 * placed on the wrong node: a refactor that reorders registrations, or an operator subcommand added
 * without its own check, would otherwise ship as a command anyone can run.
 *
 * <p>The player-facing surface is an exact list. A new command is closed to ordinary players until
 * it is added to that list on purpose, which is the right way round for a permission test.
 */
class RaidCommandGatingTest {

    /** Every command an ordinary (level 0) player may run. Anything not listed here must need an operator. */
    private static final Set<String> PLAYER_COMMANDS = Set.of(
            "",
            "reward",
            "reward claim <choice>",
            "points",
            "shop",
            "shop buy <entry>",
            "top",
            "top <stat>",
            "top <stat> <scope>",
            "top <stat> <scope> <rows>",
            "stats",
            "stats server",
            "stats <target>",
            "title",
            "title list",
            "title set <id>",
            "title clear",
            "info <pokemon>",
            "leave",
            "notify",
            "notify legends",
            // World Boss: players join, leave and look. start, stop and reload are operator-only and
            // so must never appear in this list.
            "worldboss join",
            "worldboss leave",
            "worldboss status",
            "worldboss me");

    private static CommandSourceStack source(int permissionLevel) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permissionLevel,
                "test", Component.literal("test"), null, null);
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RaidCommands.buildAll(dispatcher);
        return dispatcher;
    }

    /** Every executable command the source can actually reach, as e.g. {@code shop buy <entry>}. */
    private static Set<String> reachable(String root, CommandSourceStack source) {
        Set<String> paths = new TreeSet<>();
        CommandNode<CommandSourceStack> node = dispatcher().getRoot().getChild(root);
        collect(node, source, "", paths);
        return paths;
    }

    private static void collect(CommandNode<CommandSourceStack> node, CommandSourceStack source, String path,
                                Set<String> out) {
        if (!node.canUse(source)) return;
        if (node.getCommand() != null) out.add(path);
        CommandNode<CommandSourceStack> target = node.getRedirect() != null ? node.getRedirect() : node;
        for (CommandNode<CommandSourceStack> child : target.getChildren()) {
            String token = child instanceof ArgumentCommandNode<?, ?> ? "<" + child.getName() + ">" : child.getName();
            collect(child, source, path.isEmpty() ? token : path + " " + token, out);
        }
    }

    @Test
    @DisplayName("an ordinary player can run exactly the player commands, and nothing else")
    void playersGetExactlyThePlayerSurface() {
        assertEquals(new TreeSet<>(PLAYER_COMMANDS), reachable("cobbleraids", source(0)));
    }

    @Test
    @DisplayName("the /cr alias exposes the same commands to a player as /cobbleraids, no more")
    void aliasIsNotAWayAroundAGate() {
        assertEquals(reachable("cobbleraids", source(0)), reachable("cr", source(0)));
        assertEquals(reachable("cobbleraids", source(2)), reachable("cr", source(2)));
    }

    @Test
    @DisplayName("an operator can run everything a player can, plus the admin tree")
    void operatorsGetEverything() {
        Set<String> op = reachable("cobbleraids", source(2));

        assertTrue(op.containsAll(PLAYER_COMMANDS), "an operator lost a player command");
        for (String admin : Set.of("spawn <pokemon>", "despawn", "despawn all", "reload", "list",
                "points give <target> <amount>", "points take <target> <amount>", "points set <target> <amount>",
                "reward grant <target> <definition>", "reward clear <target>", "capture grant <target> <definition>",
                "debug audit", "debug status", "perf")) {
            assertTrue(op.contains(admin), "operator cannot reach: " + admin);
        }
    }

    @Test
    @DisplayName("nothing that changes state or reads other players' data is open to a level-0 player")
    void mutatingCommandsAreAlwaysGated() {
        Set<String> player = reachable("cobbleraids", source(0));
        for (String path : player) {
            String head = path.split(" ")[0];
            assertTrue(!Set.of("spawn", "despawn", "reload", "cooldown", "capture", "debug", "perf", "spawninfo",
                    "list").contains(head), "an admin command is open to players: " + path);
        }
        // points <target> shows someone else's balance and give/take/set rewrite it.
        assertTrue(player.stream().noneMatch(path -> path.startsWith("points ")), "points sub-commands are open");
        assertTrue(player.stream().noneMatch(path -> path.startsWith("reward grant")
                || path.startsWith("reward clear") || path.startsWith("reward list")), "reward admin open");
    }

    @Test
    @DisplayName("the retired commands are really gone")
    void retiredCommandsAreGone() {
        Set<String> everything = reachable("cobbleraids", source(4));
        for (String retired : Set.of("menu", "trophies", "legends", "testwild", "shop open", "shop reload",
                "debug rotation", "debug definition <pokemon>")) {
            assertTrue(everything.stream().noneMatch(path -> path.equals(retired)), retired + " is still registered");
        }
    }
}
