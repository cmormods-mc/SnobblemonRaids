package com.cobbleraids.client.shop;

import com.cobbleraids.client.gui.RaidGuiLayout;
import com.cobbleraids.client.gui.RaidGuiSkin;
import com.cobbleraids.network.ShopActionPayload;
import com.cobbleraids.network.ShopEntryPayload;
import com.cobbleraids.network.ShopPagePayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The raid shop.
 *
 * <p>A plain {@link Screen}, not an AbstractContainerScreen. A shop is a catalogue, not an
 * inventory: there is nothing here to pick up, drag or shift-click, and sixty-four real slots
 * holding real stacks would be sixty-four things a player could try to take. Cells are drawn, and
 * a click sends an id.
 *
 * <p>Everything is in Minecraft's logical GUI pixels, which is the point of the sliced art: the
 * window is 203x223 to 235x255 depending on the room available, and the game applies the GUI Scale
 * afterwards. Nothing here multiplies a coordinate, a mouse position or a hitbox by that scale, and
 * nothing stretches one large texture over the screen -- which is what the previous version did,
 * and why its pixels softened at every size but one.
 *
 * <p>A cell is 18 to 22 pixels, which holds an icon and about two characters. So the price lives in
 * the tooltip and is carried in the grid by dimming what the player cannot afford; the number in
 * the corner is stock remaining, because that is the figure that changes while the screen is open.
 */
public final class RaidShopScreen extends Screen {

    private static final ResourceLocation FALLBACK_ICON =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball");

    private static RaidShopScreen open;

    private ShopPagePayload page;
    private RaidGuiLayout.Layout layout;

    /**
     * One stack per cell, built when a page arrives rather than on every frame.
     *
     * <p>This was an ItemStack allocated per cell inside render(), which at sixty-four cells and
     * sixty frames a second is nearly four thousand short-lived objects a second, for a page whose
     * contents do not change between server updates. Null where a cell holds a Pokemon.
     */
    private final List<ItemStack> icons = new ArrayList<>();
    private ItemStack fallbackIcon = ItemStack.EMPTY;
    private String headingText = "";
    private String balanceText = "";
    private int tooltipSlot = -1;
    private List<Component> tooltipLines = List.of();

    private RaidShopScreen(ShopPagePayload page) {
        super(Component.literal("Raid Shop"));
        setPage(page);
    }

    /** Rebuilds everything derived from a page. Runs once per server update, never per frame. */
    private void setPage(ShopPagePayload payload) {
        this.page = payload;
        this.tooltipSlot = -1;
        icons.clear();
        for (ShopEntryPayload entry : payload.entries()) {
            icons.add(entry.pokemon() ? null
                    : new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()), entry.count()));
        }
        if (fallbackIcon.isEmpty()) {
            fallbackIcon = new ItemStack(BuiltInRegistries.ITEM.get(FALLBACK_ICON));
        }
        cacheChrome();
    }

    /** The two trimmed strings, which only change when the page or the balance does. */
    private void cacheChrome() {
        if (font == null || layout == null) return;
        String heading = page.pageCount() > 1
                ? page.heading() + " " + (page.pageIndex() + 1) + "/" + page.pageCount()
                : page.heading();
        headingText = trimTo(heading, 50);
        balanceText = trimTo(page.balance() + " RP", layout.button().width() - 6);
    }

    /** Opens the screen, or refreshes the one already open. Called from the payload receiver. */
    public static void show(ShopPagePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (open != null && client.screen == open) {
            open.setPage(payload);
            return;
        }
        open = new RaidShopScreen(payload);
        client.setScreen(open);
    }

    /**
     * Cleanup lives here rather than in onClose(), which only runs when the screen closes itself.
     * removed() runs whenever the screen is replaced, so switching away or being disconnected
     * cannot leave a static reference to a dead screen, or Cobblemon render state alive behind it.
     */
    @Override
    public void removed() {
        if (open == this) open = null;
        icons.clear();
        tooltipLines = List.of();
        ShopPokemonPortraits.clear();
        super.removed();
    }

    @Override
    protected void init() {
        // Recomputed on every init, which is what a resize and a GUI Scale change both trigger.
        layout = RaidGuiLayout.fit(width, height).orElse(null);
        cacheChrome();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        if (layout == null) {
            // Smaller than the smallest window the art assembles into. Saying so beats drawing a
            // clipped grid whose last row cannot be clicked.
            graphics.drawCenteredString(font, "Not enough room to show the shop.",
                    width / 2, height / 2, 0xFFFF8A8A);
            return;
        }
        RaidGuiSkin.renderChrome(graphics, layout);
        if (page.pageCount() > 1) RaidGuiSkin.renderArrows(graphics, layout);

        drawHeading(graphics);
        drawBalance(graphics);
        drawCells(graphics, mouseX, mouseY, partialTicks);
    }

    private void drawHeading(GuiGraphics graphics) {
        RaidGuiLayout.Rect frame = layout.frame();
        graphics.drawCenteredString(font, headingText,
                frame.x() + frame.width() / 2, frame.y() + 24, 0xFFAFFFFF);
    }

    /**
     * The balance, on the button plate at the foot of the frame.
     *
     * <p>White, because the plate underneath is not dark. Sampling the kit's own BUTTON_CENTER
     * slice gives a mean of (0, 127, 249) -- a bright, saturated blue at roughly 0.22 relative
     * luminance -- and the near-black this used to be sat at 4.0:1 against it, which the drop
     * shadow then muddied further. White reaches 3.9:1 against the plate on its own and 10:1
     * against its own shadow, and it is the shadow that carries a one-pixel glyph.
     */
    private void drawBalance(GuiGraphics graphics) {
        RaidGuiLayout.Rect button = layout.button();
        graphics.drawCenteredString(font, balanceText, button.x() + button.width() / 2,
                button.y() + (button.height() - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
    }

    private String trimTo(String text, int pixels) {
        if (font.width(text) <= pixels) return text;
        StringBuilder trimmed = new StringBuilder(text);
        while (trimmed.length() > 1 && font.width(trimmed + "...") > pixels) {
            trimmed.deleteCharAt(trimmed.length() - 1);
        }
        return trimmed + "...";
    }

    private void drawCells(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int hovered = layout.slotAt(mouseX, mouseY);
        List<ShopEntryPayload> entries = page.entries();
        List<RaidGuiLayout.Rect> slots = layout.slots();

        for (int slot = 0; slot < slots.size() && slot < entries.size(); slot++) {
            RaidGuiLayout.Rect rect = slots.get(slot);
            ShopEntryPayload entry = entries.get(slot);
            boolean affordable = page.balance() >= entry.cost() && !entry.soldOut();

            drawContents(graphics, entry, icons.get(slot), rect, partialTicks);
            if (!affordable) {
                graphics.fill(rect.x(), rect.y(), rect.x() + rect.width(),
                        rect.y() + rect.height(), 0x99070A10);
            }
            drawStock(graphics, entry, rect);
            if (slot == hovered) {
                graphics.fill(rect.x(), rect.y(), rect.x() + rect.width(),
                        rect.y() + rect.height(), 0x40FFFFFF);
            }
        }
        if (hovered >= 0 && hovered < entries.size()) {
            // Rebuilt only when the cursor moves to a different cell, not on every frame it rests
            // on the same one.
            if (hovered != tooltipSlot) {
                tooltipSlot = hovered;
                tooltipLines = tooltipFor(entries.get(hovered), icons.get(hovered));
            }
            graphics.renderComponentTooltip(font, tooltipLines, mouseX, mouseY);
        } else {
            tooltipSlot = -1;
        }
    }

    private void drawContents(GuiGraphics graphics, ShopEntryPayload entry, ItemStack icon,
                              RaidGuiLayout.Rect rect, float partialTicks) {
        if (entry.pokemon()) {
            // Three routes, best first. A drawn icon beats a rendered model here for more than
            // taste: it is one blit against a full posed model with its own animation state, and
            // hand-drawn art reads at twenty pixels where a scaled-down model turns to mush.
            if (drawSpeciesIcon(graphics, entry, rect)) return;
            if (ShopPokemonPortraits.draw(graphics, entry.species(), entry.shiny(),
                    rect.x(), rect.y(), rect.width(), partialTicks)) {
                return;
            }
            // A species name does not fit in a twenty-pixel cell, so a model that will not resolve
            // falls back to a Poke Ball: it still reads as "a Pokemon", and the tooltip names it.
            graphics.renderItem(fallbackIcon, RaidGuiSkin.itemX(rect), RaidGuiSkin.itemY(rect));
            return;
        }
        ItemStack stack = icon;
        int x = RaidGuiSkin.itemX(rect);
        int y = RaidGuiSkin.itemY(rect);
        graphics.renderItem(stack, x, y);
        // Vanilla's own decoration, so a stack count sits exactly where a player expects it.
        graphics.renderItemDecorations(font, stack, x, y);
    }

    /**
     * The species' card icon, scaled into the cell and centred.
     *
     * <p>Fitted by the narrower of the two ratios rather than stretched to the cell: the art is
     * 48x32 and a square cell would squash it, which is more obvious on a Pokemon than on anything
     * else in the grid.
     */
    private boolean drawSpeciesIcon(GuiGraphics graphics, ShopEntryPayload entry,
                                    RaidGuiLayout.Rect rect) {
        ResourceLocation texture = ShopSpeciesIcons.texture(entry.species(), entry.shiny());
        if (texture == null) return false;
        int cell = rect.width();
        float scale = Math.min(cell / (float) ShopSpeciesIcons.WIDTH,
                               cell / (float) ShopSpeciesIcons.HEIGHT);
        int width = Math.max(1, Math.round(ShopSpeciesIcons.WIDTH * scale));
        int height = Math.max(1, Math.round(ShopSpeciesIcons.HEIGHT * scale));
        graphics.blit(texture,
                rect.x() + (cell - width) / 2, rect.y() + (cell - height) / 2,
                width, height, 0.0F, 0.0F,
                ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT,
                ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT);
        return true;
    }

    /**
     * How many of a limited entry are left, in the cell's top-left corner.
     *
     * <p>The remaining count alone, not "3/5": at this size a slash costs a third of the width, and
     * the figure a player acts on is how many they can still buy. The limit is in the tooltip.
     */
    private void drawStock(GuiGraphics graphics, ShopEntryPayload entry, RaidGuiLayout.Rect rect) {
        if (!entry.isLimited()) return;
        graphics.drawString(font, String.valueOf(Math.max(0, entry.remaining())),
                rect.x() + 1, rect.y() + 1,
                entry.soldOut() ? 0xFFFF8A8A : 0xFFB8D8EA, true);
    }

    private List<Component> tooltipFor(ShopEntryPayload entry, ItemStack icon) {
        List<Component> lines = new ArrayList<>();
        if (entry.pokemon()) {
            lines.add(Component.literal((entry.shiny() ? "Shiny " : "") + capitalise(entry.species()))
                    .withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Level " + entry.level()).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(icon.getHoverName());
            lines.add(Component.literal("x" + entry.count()).withStyle(ChatFormatting.GRAY));
        }
        if (entry.soldOut()) {
            lines.add(Component.literal(entry.limit() == 1
                            ? "Already purchased" : "All " + entry.limit() + " bought")
                    .withStyle(ChatFormatting.GREEN));
            lines.add(Component.literal("Resets daily").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal(entry.cost() + " RP").withStyle(
                    page.balance() >= entry.cost() ? ChatFormatting.AQUA : ChatFormatting.RED));
            if (page.balance() < entry.cost()) {
                lines.add(Component.literal((entry.cost() - page.balance()) + " RP short")
                        .withStyle(ChatFormatting.DARK_RED));
            }
            if (entry.isLimited()) {
                lines.add(Component.literal(entry.remaining() + " of " + entry.limit() + " left")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return List.copyOf(lines);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && layout != null) {
            if (page.pageCount() > 1 && layout.previous().contains(mouseX, mouseY)) {
                turn(-1);
                return true;
            }
            if (page.pageCount() > 1 && layout.next().contains(mouseX, mouseY)) {
                turn(1);
                return true;
            }
            int slot = layout.slotAt(mouseX, mouseY);
            if (slot >= 0 && slot < page.entries().size()) {
                // The click carries the id and the page it was clicked on. The server re-reads
                // everything else, so a stale page or a hostile client buys nothing unusual.
                ClientPlayNetworking.send(
                        ShopActionPayload.buy(page.pageIndex(), page.entries().get(slot).id()));
                click();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void turn(int direction) {
        int next = Math.floorMod(page.pageIndex() + direction, Math.max(1, page.pageCount()));
        ClientPlayNetworking.send(ShopActionPayload.turnTo(next));
        click();
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
