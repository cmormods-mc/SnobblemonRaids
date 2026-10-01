package com.cobbleraids.shop;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which species are starters, and how far along their line they are.
 *
 * <p>A fixed list rather than something read from the species registry: Cobblemon carries no
 * "starter" label, and the question being asked is "which of these is the one a player is really
 * shopping for", which is a human judgement about three Pokemon per generation. Every line is three
 * stages, so a stage is just a position. Only Cobblemon's own species count: an add-on's
 * {@code somemod:charmander} is not the Charmander this prices.
 *
 * <p>Free of Minecraft and Cobblemon types, so it is unit-tested whole.
 */
final class ShopStarterLines {

    enum Stage { BASE, MIDDLE, FINAL }

    private static final List<List<String>> LINES = List.of(
            List.of("bulbasaur", "ivysaur", "venusaur"),
            List.of("charmander", "charmeleon", "charizard"),
            List.of("squirtle", "wartortle", "blastoise"),
            List.of("chikorita", "bayleef", "meganium"),
            List.of("cyndaquil", "quilava", "typhlosion"),
            List.of("totodile", "croconaw", "feraligatr"),
            List.of("treecko", "grovyle", "sceptile"),
            List.of("torchic", "combusken", "blaziken"),
            List.of("mudkip", "marshtomp", "swampert"),
            List.of("turtwig", "grotle", "torterra"),
            List.of("chimchar", "monferno", "infernape"),
            List.of("piplup", "prinplup", "empoleon"),
            List.of("snivy", "servine", "serperior"),
            List.of("tepig", "pignite", "emboar"),
            List.of("oshawott", "dewott", "samurott"),
            List.of("chespin", "quilladin", "chesnaught"),
            List.of("fennekin", "braixen", "delphox"),
            List.of("froakie", "frogadier", "greninja"),
            List.of("rowlet", "dartrix", "decidueye"),
            List.of("litten", "torracat", "incineroar"),
            List.of("popplio", "brionne", "primarina"),
            List.of("grookey", "thwackey", "rillaboom"),
            List.of("scorbunny", "raboot", "cinderace"),
            List.of("sobble", "drizzile", "inteleon"),
            List.of("sprigatito", "floragato", "meowscarada"),
            List.of("fuecoco", "crocalor", "skeledirge"),
            List.of("quaxly", "quaxwell", "quaquaval"));

    private static final Map<String, Stage> STAGES = new HashMap<>();

    static {
        for (List<String> line : LINES) {
            for (int position = 0; position < line.size(); position++) {
                STAGES.put(line.get(position), Stage.values()[position]);
            }
        }
    }

    private ShopStarterLines() {}

    /** The stage of {@code speciesId} ("charmeleon" or "cobblemon:charmeleon"), or empty if it is no starter. */
    static Optional<Stage> stageOf(String speciesId) {
        if (speciesId == null) return Optional.empty();
        String id = speciesId.toLowerCase(Locale.ROOT);
        if (id.startsWith("cobblemon:")) id = id.substring("cobblemon:".length());
        return Optional.ofNullable(STAGES.get(id));
    }

    static int lineCount() {
        return LINES.size();
    }
}
