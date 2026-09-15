package com.cobbleraids.renown;

import java.util.regex.Pattern;

/**
 * A renowned boss's identity: "Kaelen, the Relentless", and what that title does.
 *
 * <p>Holds values, not ids into the word lists. A boss keeps the title it rolled even if a
 * {@code /reload} removes that name from every datapack, which is the same choice
 * PendingRaidReward makes for an earned reward: a reload must not rewrite something that already
 * exists in the world.
 *
 * <p>The shape rules live here rather than in the loader because two untrusted sources build one:
 * datapack JSON and the entity tags a boss is saved with. A name may not contain a comma because
 * the title is split back into name and epithet on the client at the first ", ".
 */
public record RaidRenown(String name, String epithet, RenownBoon boon) {

    public static final int MAX_NAME_LENGTH = 12;
    public static final int MAX_EPITHET_LENGTH = 20;

    private static final Pattern NAME = Pattern.compile("\\p{Lu}\\p{L}{2," + (MAX_NAME_LENGTH - 1) + "}");
    private static final Pattern EPITHET = Pattern.compile("the \\p{Lu}[\\p{L}-]*( \\p{Lu}[\\p{L}-]*)?");

    public RaidRenown {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("renown name must be one capitalised word of 3-"
                    + MAX_NAME_LENGTH + " letters, got '" + name + "'");
        if (epithet == null || epithet.length() > MAX_EPITHET_LENGTH || !EPITHET.matcher(epithet).matches())
            throw new IllegalArgumentException("renown epithet must read 'the Word' or 'the Two Words', at most "
                    + MAX_EPITHET_LENGTH + " characters, got '" + epithet + "'");
        if (boon == null) throw new IllegalArgumentException("renown boon cannot be null");
    }

    /** "Kaelen, the Relentless" -- what the reward screen shows and what a pending reward remembers. */
    public String title() {
        return name + ", " + epithet;
    }

    /** Splits a {@link #title()} back into its name and epithet; null halves when it is not one. */
    public static String[] splitTitle(String title) {
        if (title == null) return new String[] {null, null};
        int comma = title.indexOf(", ");
        if (comma <= 0) return new String[] {title.isBlank() ? null : title, null};
        return new String[] {title.substring(0, comma), title.substring(comma + 2)};
    }
}
