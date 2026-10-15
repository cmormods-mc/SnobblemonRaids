package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * C2S: the player picked this reward choice on the native screen. raidId is a defensive echo only --
 * the server always resolves the pending reward from its own queue, never from this field's identity.
 */
public record RewardChoicePayload(UUID raidId, String choiceId) {
    /** Longer than any choice id a reward definition will declare, so a hostile client cannot send a novel. */
    public static final int MAX_CHOICE_ID_LENGTH = 64;
}
