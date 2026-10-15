package com.cobbleraids.item;

import com.cobbleraids.RaidLog;
import com.cobbleraids.fault.RaidFaultBarrier;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Adds this mod's item models and textures to the resource pack Polymer builds and serves.
 *
 * <p>Only the item art is copied, not the whole {@code assets/cobbleraids} folder: the rest of it
 * is read by the server (web page art, Showdown scripts) and would only make the download bigger.
 */
public final class RaidItemPack {
    private RaidItemPack() {}

    /** Every model/texture name under {@code models/item} and {@code textures/item}. */
    static final List<String> NAMES = List.of(
            "starter_raid_key", "powerhouse_raid_key", "mythical_raid_key", "legendary_raid_key",
            "starter_raid_key_fragment", "powerhouse_raid_key_fragment", "mythical_raid_key_fragment",
            "legendary_raid_key_fragment", "raid_core", "guardian_scale");

    public static void register() {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder ->
                RaidFaultBarrier.guard("resource-pack:item-art", () -> {
                    for (String name : NAMES) {
                        add(builder, "assets/cobbleraids/models/item/" + name + ".json");
                        add(builder, "assets/cobbleraids/textures/item/" + name + ".png");
                    }
                }));
    }

    private static void add(eu.pb4.polymer.resourcepack.api.ResourcePackBuilder builder, String path) {
        try (InputStream in = RaidItemPack.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                RaidLog.error("Item art is missing from the jar: {}", path);
                return;
            }
            builder.addData(path, in.readAllBytes());
        } catch (IOException e) {
            RaidLog.error("Could not read item art {}: {}", path, e.toString());
        }
    }
}
