package com.cobbleraids.network;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * S2C: one stabilization pulse has been judged. Sent the instant the server scores it -- not batched
 * with the other two -- so the client can show feedback (Unstable/Stable/Perfect) as each pulse lands
 * rather than waiting for the whole sequence.
 */
public record CapturePulseResultPayload(UUID raidId, int pulseIndex, double score) {}
