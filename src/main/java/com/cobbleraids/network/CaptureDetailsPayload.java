package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: optional display metadata for the Raid Capture Protocol's offer/minigame screens -- the
 * boss's actual species (so the chamber can render its sprite instead of a placeholder), this
 * tier's rate figures (so the odds line reads real numbers instead of "server configured"), and the
 * sequence timeout (so the minigame screen's own countdown matches what the server will actually
 * enforce). Sent alongside {@link CaptureOfferPayload}, never in place of it: every existing input,
 * offer and result packet is unaffected if this one is dropped or never sent, and the screens fall
 * back to generic text rather than inventing a number.
 */
public record CaptureDetailsPayload(
        UUID raidId,
        ResourceLocation species,
        double base,
        double stabilizationCap,
        double throwCap,
        int sequenceSeconds
) {}
