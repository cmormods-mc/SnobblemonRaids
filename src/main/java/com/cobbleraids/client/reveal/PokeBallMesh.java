package com.cobbleraids.client.reveal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A hand-built, procedurally generated Poke Ball -- not a Blockbench/JSON model, not Cobblemon's own
 * ball renderer, so this mod has full frame-by-frame control over the open/shake/spin animation.
 * Geometry is generated exactly once (below) since it never changes; only the transform (rotation,
 * per-hemisphere separation) changes per frame.
 */
final class PokeBallMesh {
    private static final int RED = FastColor.ARGB32.color(255, 208, 42, 32);
    private static final int WHITE = FastColor.ARGB32.color(255, 240, 240, 240);
    private static final int BLACK = FastColor.ARGB32.color(255, 24, 24, 24);

    private static final int LATITUDE_SEGMENTS = 12;
    private static final int LONGITUDE_SEGMENTS = 16;
    private static final int BUTTON_SEGMENTS = 12;
    private static final float BAND_RADIUS = 1.03f;
    private static final float BAND_HALF_HEIGHT = 0.12f;

    private static final Vec3 LIGHT_DIR = new Vec3(0.4f, 0.8f, 0.5f).normalize();

    private static final GeneratedMesh MESH = generate();

    private PokeBallMesh() {}

    record Vec3(float x, float y, float z) {
        Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
        Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
        Vec3 scale(float s) { return new Vec3(x * s, y * s, z * s); }
        Vec3 cross(Vec3 o) { return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
        float dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
        float length() { return (float) Math.sqrt(x * x + y * y + z * z); }
        Vec3 normalize() { float len = length(); return len < 1.0e-6f ? this : scale(1f / len); }
    }

    record Triangle(Vec3 v0, Vec3 v1, Vec3 v2, int baseColor) {
        Vec3 faceNormal() { return v1.sub(v0).cross(v2.sub(v0)).normalize(); }
    }

    record GeneratedMesh(List<Triangle> topHemisphere, List<Triangle> bottomHemisphere,
                          List<Triangle> band, List<Triangle> button) {}

    /** Converts a tier's chat color into an ARGB int at the given alpha, for the flash/particle tint. */
    static int argbFromChatFormatting(ChatFormatting formatting, int alpha) {
        Integer rgb = formatting.getColor();
        int base = rgb != null ? rgb : 0xFFFFFF;
        return (alpha << 24) | (base & 0xFFFFFF);
    }

    /**
     * Draws the ball centered at (centerX, centerY) with the given pixel radius and current rotation.
     * openSeparation is 0 (closed) to 1 (fully open); flashStrength is 0 (none) to 1 (peak), tinted
     * flashColor. Ends with graphics.flush() so this draw completes before subsequent text/item draws
     * queue into the same shared buffer.
     */
    static void render(GuiGraphics graphics, int centerX, int centerY, float radiusPx,
                        Quaternionf rotation, float openSeparation, int flashColor, float flashStrength) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        pose.scale(radiusPx, -radiusPx, radiusPx);
        pose.mulPose(rotation);

        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.debugQuads());
        float shellOffset = openSeparation * 0.6f;
        drawOffset(consumer, pose, MESH.topHemisphere(), 0f, shellOffset, 0f);
        drawOffset(consumer, pose, MESH.bottomHemisphere(), 0f, -shellOffset, 0f);
        drawOffset(consumer, pose, MESH.band(), 0f, 0f, 0f);
        drawOffset(consumer, pose, MESH.button(), 0f, 0f, 0f);

        graphics.flush();
        pose.popPose();

        if (flashStrength > 0f) {
            int glowRadius = Math.round(radiusPx * 1.3f);
            int alpha = Math.round(Math.min(1f, flashStrength) * 200f);
            int tinted = FastColor.ARGB32.color(alpha, FastColor.ARGB32.red(flashColor),
                    FastColor.ARGB32.green(flashColor), FastColor.ARGB32.blue(flashColor));
            graphics.fill(centerX - glowRadius, centerY - glowRadius, centerX + glowRadius, centerY + glowRadius, tinted);
        }
    }

    private static void drawOffset(VertexConsumer consumer, PoseStack pose, List<Triangle> triangles,
                                    float ox, float oy, float oz) {
        pose.pushPose();
        if (ox != 0f || oy != 0f || oz != 0f) pose.translate(ox, oy, oz);
        Matrix4f m = pose.last().pose();
        for (Triangle t : triangles) {
            int shaded = shade(t);
            // Degenerate 4th vertex: RenderType.debugQuads() is QUADS mode, and a triangle drawn as a
            // quad with its last vertex repeated renders identically to a true triangle.
            consumer.addVertex(m, t.v0().x(), t.v0().y(), t.v0().z()).setColor(shaded);
            consumer.addVertex(m, t.v1().x(), t.v1().y(), t.v1().z()).setColor(shaded);
            consumer.addVertex(m, t.v2().x(), t.v2().y(), t.v2().z()).setColor(shaded);
            consumer.addVertex(m, t.v2().x(), t.v2().y(), t.v2().z()).setColor(shaded);
        }
        pose.popPose();
    }

    private static int shade(Triangle t) {
        float factor = 0.5f + 0.5f * Math.max(0f, t.faceNormal().dot(LIGHT_DIR));
        int r = Math.round(FastColor.ARGB32.red(t.baseColor()) * factor);
        int g = Math.round(FastColor.ARGB32.green(t.baseColor()) * factor);
        int b = Math.round(FastColor.ARGB32.blue(t.baseColor()) * factor);
        return FastColor.ARGB32.color(FastColor.ARGB32.alpha(t.baseColor()), r, g, b);
    }

    private static GeneratedMesh generate() {
        List<Triangle> top = new ArrayList<>();
        List<Triangle> bottom = new ArrayList<>();
        List<Triangle> band = new ArrayList<>();
        List<Triangle> button = new ArrayList<>();

        generateSphere(top, bottom);
        generateBand(band);
        generateButton(button);

        return new GeneratedMesh(List.copyOf(top), List.copyOf(bottom), List.copyOf(band), List.copyOf(button));
    }

    private static void generateSphere(List<Triangle> top, List<Triangle> bottom) {
        for (int i = 0; i < LATITUDE_SEGMENTS; i++) {
            float phi0 = (float) (-Math.PI / 2 + Math.PI * i / LATITUDE_SEGMENTS);
            float phi1 = (float) (-Math.PI / 2 + Math.PI * (i + 1) / LATITUDE_SEGMENTS);
            for (int j = 0; j < LONGITUDE_SEGMENTS; j++) {
                float theta0 = (float) (2 * Math.PI * j / LONGITUDE_SEGMENTS);
                float theta1 = (float) (2 * Math.PI * (j + 1) / LONGITUDE_SEGMENTS);

                Vec3 p00 = spherePoint(phi0, theta0);
                Vec3 p01 = spherePoint(phi0, theta1);
                Vec3 p10 = spherePoint(phi1, theta0);
                Vec3 p11 = spherePoint(phi1, theta1);

                boolean isTop = (phi0 + phi1) >= 0f;
                int color = isTop ? RED : WHITE;
                List<Triangle> target = isTop ? top : bottom;

                target.add(outwardFacing(new Triangle(p00, p10, p11, color)));
                target.add(outwardFacing(new Triangle(p00, p11, p01, color)));
            }
        }
    }

    private static Vec3 spherePoint(float phi, float theta) {
        float cosPhi = (float) Math.cos(phi);
        return new Vec3(cosPhi * (float) Math.cos(theta), (float) Math.sin(phi), cosPhi * (float) Math.sin(theta));
    }

    private static void generateBand(List<Triangle> band) {
        for (int j = 0; j < LONGITUDE_SEGMENTS; j++) {
            float theta0 = (float) (2 * Math.PI * j / LONGITUDE_SEGMENTS);
            float theta1 = (float) (2 * Math.PI * (j + 1) / LONGITUDE_SEGMENTS);
            Vec3 top0 = new Vec3(BAND_RADIUS * (float) Math.cos(theta0), BAND_HALF_HEIGHT, BAND_RADIUS * (float) Math.sin(theta0));
            Vec3 top1 = new Vec3(BAND_RADIUS * (float) Math.cos(theta1), BAND_HALF_HEIGHT, BAND_RADIUS * (float) Math.sin(theta1));
            Vec3 bot0 = new Vec3(BAND_RADIUS * (float) Math.cos(theta0), -BAND_HALF_HEIGHT, BAND_RADIUS * (float) Math.sin(theta0));
            Vec3 bot1 = new Vec3(BAND_RADIUS * (float) Math.cos(theta1), -BAND_HALF_HEIGHT, BAND_RADIUS * (float) Math.sin(theta1));
            band.add(outwardFacing(new Triangle(bot0, bot1, top1, BLACK)));
            band.add(outwardFacing(new Triangle(bot0, top1, top0, BLACK)));
        }
    }

    private static void generateButton(List<Triangle> button) {
        generateDisc(button, 0.28f, 1.045f, BLACK);
        generateDisc(button, 0.20f, 1.05f, WHITE);
    }

    private static void generateDisc(List<Triangle> out, float radius, float z, int color) {
        Vec3 center = new Vec3(0f, 0f, z);
        for (int i = 0; i < BUTTON_SEGMENTS; i++) {
            float a0 = (float) (2 * Math.PI * i / BUTTON_SEGMENTS);
            float a1 = (float) (2 * Math.PI * (i + 1) / BUTTON_SEGMENTS);
            Vec3 p0 = new Vec3(radius * (float) Math.cos(a0), radius * (float) Math.sin(a0), z);
            Vec3 p1 = new Vec3(radius * (float) Math.cos(a1), radius * (float) Math.sin(a1), z);
            out.add(outwardFacing(new Triangle(center, p0, p1, color)));
        }
    }

    /** Self-corrects winding so every triangle's face normal points away from the ball's center. */
    private static Triangle outwardFacing(Triangle t) {
        Vec3 centroid = t.v0().add(t.v1()).add(t.v2()).scale(1f / 3f);
        if (t.faceNormal().dot(centroid) < 0f) {
            return new Triangle(t.v0(), t.v2(), t.v1(), t.baseColor());
        }
        return t;
    }
}
