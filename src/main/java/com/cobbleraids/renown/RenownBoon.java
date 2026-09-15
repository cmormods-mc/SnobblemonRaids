package com.cobbleraids.renown;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a renowned boss's epithet does to the fight.
 *
 * <p>A closed set on purpose. An epithet names a kind and, for a stat focus, which stat; how strong
 * that is comes from the server config, never from the datapack. So a third-party word list can
 * give a boss "the Relentless" but cannot make it twice as strong as the operator agreed to, and
 * retuning every renowned boss is one number rather than an edit to every file.
 *
 * <p>Deliberately missing: damage multipliers, healing and anything that reads or writes HP inside
 * the simulator. raid-patch.js pins the boss's simulated HP, which is exactly why RaidBossTraits
 * refuses Leftovers and Focus Sash, and the same reasoning applies here.
 *
 * <p>Written as one string -- {@code "hp_pool"}, {@code "stat_focus:attack"} -- both in datapack
 * JSON and in the entity tag that remembers it, so there is a single codec to get wrong.
 */
public record RenownBoon(Kind kind, String stat) {

    public enum Kind {
        /** Title only. Allowed in a datapack; nothing shipped uses it. */
        NONE,
        /** A larger shared raid health pool, sized at the moment recruitment locks. */
        HP_POOL,
        /** Maximum IV and extra EVs in one stat, applied when the boss spawns. */
        STAT_FOCUS;

        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * The stats a focus may name. HP is left out because a boss's own HP is not what players chew
     * through -- the raid pool is -- so investing in it would be a boon that does nothing.
     */
    public static final Set<String> FOCUS_STATS =
            Set.of("attack", "defence", "special_attack", "special_defence", "speed");

    public static final RenownBoon NONE = new RenownBoon(Kind.NONE, null);
    public static final RenownBoon HP_POOL = new RenownBoon(Kind.HP_POOL, null);

    public RenownBoon {
        if (kind == null) throw new IllegalArgumentException("boon kind cannot be null");
        if (kind == Kind.STAT_FOCUS) {
            if (stat == null || !FOCUS_STATS.contains(stat))
                throw new IllegalArgumentException("stat_focus needs one of " + FOCUS_STATS + ", got " + stat);
        } else if (stat != null) {
            throw new IllegalArgumentException(kind.serializedName() + " does not take a stat");
        }
    }

    public static RenownBoon statFocus(String stat) {
        return new RenownBoon(Kind.STAT_FOCUS, stat);
    }

    public String encode() {
        return kind == Kind.STAT_FOCUS ? kind.serializedName() + ":" + stat : kind.serializedName();
    }

    /** The inverse of {@link #encode}. Empty rather than throwing, since tags and datapacks are both untrusted. */
    public static Optional<RenownBoon> decode(String value) {
        if (value == null) return Optional.empty();
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        // American spellings accepted, matching RaidBossTraits, so an author need not guess.
        trimmed = trimmed.replace("defense", "defence");
        try {
            if (trimmed.equals("none")) return Optional.of(NONE);
            if (trimmed.equals("hp_pool")) return Optional.of(HP_POOL);
            if (trimmed.startsWith("stat_focus:")) return Optional.of(statFocus(trimmed.substring("stat_focus:".length())));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
