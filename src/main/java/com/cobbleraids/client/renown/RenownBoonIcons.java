package com.cobbleraids.client.renown;

import com.cobbleraids.renown.RenownBoon;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-only: maps a renowned boss's boon to the 16x16 icon that shows what it buffed. Shared by
 * the reward reveal screen's chamber banner and the boss's own floating nameplate so the two
 * surfaces can never disagree about which icon a boon gets.
 */
public final class RenownBoonIcons {
    private static final ResourceLocation HP_POOL = texture("boon_hp_pool");
    private static final ResourceLocation ATTACK = texture("boon_attack");
    private static final ResourceLocation DEFENCE = texture("boon_defence");
    private static final ResourceLocation SPECIAL_ATTACK = texture("boon_special_attack");
    private static final ResourceLocation SPECIAL_DEFENCE = texture("boon_special_defence");
    private static final ResourceLocation SPEED = texture("boon_speed");

    private RenownBoonIcons() {}

    /** Null for {@link RenownBoon.Kind#NONE} or an unrecognised stat. */
    public static ResourceLocation iconFor(RenownBoon boon) {
        return switch (boon.kind()) {
            case HP_POOL -> HP_POOL;
            case STAT_FOCUS -> switch (boon.stat()) {
                case "attack" -> ATTACK;
                case "defence" -> DEFENCE;
                case "special_attack" -> SPECIAL_ATTACK;
                case "special_defence" -> SPECIAL_DEFENCE;
                case "speed" -> SPEED;
                default -> null;
            };
            case NONE -> null;
        };
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("cobbleraids", "textures/gui/raid_rewards/" + name + ".png");
    }
}
