package com.cobbleraids.mixin.battle;

import kotlin.text.Regex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cobblemon's own PNX letter assignment (Targetable.getLetter()) supports up to 6 simultaneously
 * active Pokemon on one side (letters a-f), but the separate regex it uses to validate an
 * *incoming* PNX token (BattleMessage.PNX_MATCHER) only accepts a-c -- the max ever reached by any
 * stock format (triples). No stock format puts more than 3 actors' Pokemon on one side, so this
 * inconsistency between Cobblemon's own letter-generation and letter-validation ranges never
 * surfaces there.
 *
 * A raid puts every player actor on one shared Java BattleSide, so a 4th player's Pokemon is
 * assigned letter 'd' by Cobblemon's own getLetter(), which Cobblemon's own PNX_MATCHER then
 * rejects: every Showdown instruction referencing that Pokemon (boosts, damage, switches, ...)
 * throws InvalidInstructionException, which can abort interpretation of the whole message batch --
 * confirmed live via a real 4-player raid log: "Failed to interpret |-boost|p4d: ...".
 *
 * Widens the regex to match what getLetter() already produces (a-f, i.e. up to 6 actors sharing a
 * side) instead of touching the letter-assignment scheme itself, which raid-patch.js's
 * Pokemon.getSlot() override deliberately mirrors to keep the JS and Java sides in agreement.
 */
@Mixin(targets = "com.cobblemon.mod.common.api.battles.interpreter.BattleMessage")
public abstract class RaidBattleMessagePnxMixin {
    @Redirect(
        method = "<clinit>",
        at = @At(value = "NEW", target = "kotlin/text/Regex")
    )
    private static Regex cobbleRaids$widenPnxMatcher(String pattern) {
        return new Regex("p\\d[a-c]".equals(pattern) ? "p\\d[a-f]" : pattern);
    }
}
