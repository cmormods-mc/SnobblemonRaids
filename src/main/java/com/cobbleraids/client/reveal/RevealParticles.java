package com.cobbleraids.client.reveal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * A hand-rolled GUI-space particle burst. Minecraft's ParticleEngine only operates in 3D world
 * coordinates, so a Screen cannot ask it for a screen-space effect -- this is a small, self-contained
 * substitute using vanilla's existing spark sprite (no new assets), bounded to one burst's worth of
 * sparks and culled once they expire.
 */
final class RevealParticles {
    private static final int SPARK_COUNT = 16;
    private static final long LIFETIME_MILLIS = 500L;
    private static final int FRAME_COUNT = 8;
    private static final int FRAME_SIZE = 8;

    private record Spark(float x, float y, float vx, float vy, long spawnedAtMillis) {}

    private static final List<Spark> ACTIVE = new ArrayList<>();

    private RevealParticles() {}

    static void spawnBurst(float centerX, float centerY) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < SPARK_COUNT; i++) {
            double angle = 2 * Math.PI * i / SPARK_COUNT;
            float speed = 40f + (i % 3) * 15f;
            float vx = (float) Math.cos(angle) * speed;
            float vy = (float) Math.sin(angle) * speed;
            ACTIVE.add(new Spark(centerX, centerY, vx, vy, now));
        }
    }

    static void renderAndCull(GuiGraphics graphics) {
        if (ACTIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        ACTIVE.removeIf(spark -> now - spark.spawnedAtMillis() >= LIFETIME_MILLIS);
        for (Spark spark : ACTIVE) {
            float age = (now - spark.spawnedAtMillis()) / (float) LIFETIME_MILLIS;
            int x = Math.round(spark.x() + spark.vx() * age);
            int y = Math.round(spark.y() + spark.vy() * age);
            int frame = Math.min(FRAME_COUNT - 1, (int) (age * FRAME_COUNT));
            ResourceLocation sprite = ResourceLocation.withDefaultNamespace("textures/particle/spark_" + frame + ".png");
            graphics.blit(sprite, x - FRAME_SIZE / 2, y - FRAME_SIZE / 2, 0f, 0f, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);
        }
    }
}
