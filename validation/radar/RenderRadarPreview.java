import com.cobbleraids.radar.RadarRenderer;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.minecraft.world.level.material.MapColor;

/** Real renderer and vanilla map palette, synthetic terrain. Not an in-game screenshot. */
public final class RenderRadarPreview {
    public static void main(String[] args) throws Exception {
        int[] heights = new int[33 * 33];
        for (int z = 0; z < 33; z++) for (int x = 0; x < 33; x++) {
            double hill = 110 * Math.exp(-((x - 20) * (x - 20) + (z - 12) * (z - 12)) / 44.0);
            double ridge = 62 * Math.exp(-((x - 10) * (x - 10) + (z - 23) * (z - 23)) / 35.0);
            heights[z * 33 + x] = 56 + (int) (hill + ridge + 7 * Math.sin(x * 0.4));
        }
        byte[] background = RadarRenderer.terrain(heights, 64);
        byte[] frame = RadarRenderer.frame(background, RadarRenderer.project(-45, 55, 0), -135,
                RadarRenderer.project(125, -100, 70), "241M", "LEGENDARY", false);
        boolean captured = args.length > 1;
        if (captured) {
            frame = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[1]));
            if (frame.length != 128 * 128) throw new IllegalArgumentException("Expected a captured 128x128 map");
        }
        byte[] palette = {
            MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.LOWEST),
            MapColor.TERRACOTTA_CYAN.getPackedId(MapColor.Brightness.LOWEST),
            MapColor.COLOR_CYAN.getPackedId(MapColor.Brightness.LOW),
            MapColor.DIAMOND.getPackedId(MapColor.Brightness.NORMAL),
            MapColor.DIAMOND.getPackedId(MapColor.Brightness.HIGH),
            MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH),
            MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH),
            MapColor.COLOR_CYAN.getPackedId(MapColor.Brightness.LOWEST)
        };
        BufferedImage nativeImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < frame.length; i++) {
            int abgr = MapColor.getColorFromPackedId(Byte.toUnsignedInt(captured ? frame[i] : palette[frame[i]]));
            int rgb = ((abgr & 255) << 16) | (abgr & 0xff00) | ((abgr >> 16) & 255);
            nativeImage.setRGB(i % 128, i / 128, rgb);
        }
        ImageIO.write(nativeImage, "png", new File(args[0] + "-native.png"));
        BufferedImage large = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = large.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(nativeImage, 0, 0, 768, 768, null); graphics.dispose();
        ImageIO.write(large, "png", new File(args[0] + ".png"));
        for (int i = 0; i < 100; i++) RadarRenderer.terrain(heights, 64);
        long[] timings = new long[200];
        for (int i = 0; i < timings.length; i++) {
            long start = System.nanoTime(); RadarRenderer.terrain(heights, 64); timings[i] = System.nanoTime() - start;
        }
        Arrays.sort(timings);
        System.out.printf("Synthetic terrain render, 200 warmed runs: median %.3f ms, p95 %.3f ms%n",
                timings[100] / 1e6, timings[190] / 1e6);
    }
}
