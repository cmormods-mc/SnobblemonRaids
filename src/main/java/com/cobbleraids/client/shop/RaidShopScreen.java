package com.cobbleraids.client.shop;

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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The raid shop.
 *
 * <p>A plain {@link Screen}, not an AbstractContainerScreen. A shop is a catalogue, not an
 * inventory: there is nothing here to pick up, drag or shift-click, and seventy-two real slots
 * holding real stacks would be seventy-two things a player could try to take. Cells are drawn, and
 * a click sends an id.
 *
 * <p>Laid out at the frame art's native 1160x1176 and scaled uniformly to the window, the same way
 * the reward reveal is. The cell grid comes from {@link ShopGridLayout}, which is unit-tested and
 * was checked by rendering it offline before this class existed.
 */
public final class RaidShopScreen extends Screen {

    private static final ResourceLocation FRAME =
            ResourceLocation.fromNamespaceAndPath("cobbleraids", "textures/gui/raid_shop/shop_frame.png");

    private static final float NATIVE_WIDTH = 1160f;
    private static final float NATIVE_HEIGHT = 1176f;
    /** Measured out of the art: the flat area left where the drawn grid used to be. */
    private static final NativeRect PANEL = new NativeRect(141, 189, 879, 790);
    private static final NativeRect LEFT_ARROW = new NativeRect(352, 93, 68, 59);
    private static final NativeRect RIGHT_ARROW = new NativeRect(739, 93, 68, 59);
    private static final NativeRect HEADING = new NativeRect(420, 93, 320, 59);
    private static final NativeRect BALANCE_PILL = new NativeRect(348, 1040, 464, 56);

    private static final int COLUMNS = 9;
    private static final int ROWS = 8;

    private record NativeRect(int x, int y, int width, int height) {}

    private record Rect(int x, int y, int width, int height) {
        boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
        }
    }

    private static RaidShopScreen open;

    private ShopPagePayload page;
    private ShopGridLayout grid;
    private Rect frame = new Rect(0, 0, 0, 0);
    private Rect leftArrow = new Rect(0, 0, 0, 0);
    private Rect rightArrow = new Rect(0, 0, 0, 0);
    private Rect balancePill = new Rect(0, 0, 0, 0);
    private Rect heading = new Rect(0, 0, 0, 0);
    private float scale = 1f;

    private RaidShopScreen(ShopPagePayload page) {
        super(Component.literal("Raid Shop"));
        this.page = page;
    }

    /** Opens the screen, or refreshes the one already open. Called from the payload receiver. */
    public static void show(ShopPagePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (open != null && client.screen == open) {
            open.page = payload;
            return;
        }
        open = new RaidShopScreen(payload);
        client.setScreen(open);
    }

    @Override
    public void onClose() {
        open = null;
        // These hold Cobblemon animation state, not just data; a shop nobody has open should not
        // be keeping models alive.
        ShopPokemonPortraits.clear();
        super.onClose();
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        float ratio = NATIVE_WIDTH / NATIVE_HEIGHT;
        int height = Math.min(this.height - 20, Math.max(260, this.height - 40));
        int width = Math.round(height * ratio);
        if (width > this.width - 20) {
            width = this.width - 20;
            height = Math.round(width / ratio);
        }
        int x = (this.width - width) / 2;
        int y = (this.height - height) / 2;
        scale = width / NATIVE_WIDTH;

        frame = new Rect(x, y, width, height);
        leftArrow = toScreen(LEFT_ARROW);
        rightArrow = toScreen(RIGHT_ARROW);
        heading = toScreen(HEADING);
        balancePill = toScreen(BALANCE_PILL);
        Rect panel = toScreen(PANEL);
        grid = ShopGridLayout.of(panel.x(), panel.y(), panel.width(), panel.height(), COLUMNS, ROWS);
    }

    private Rect toScreen(NativeRect rect) {
        return new Rect(frameX(rect.x()), frameY(rect.y()),
                Math.round(rect.width() * scale), Math.round(rect.height() * scale));
    }

    private int frameX(int nativeX) {
        return frame.x() + Math.round(nativeX * scale);
    }

    private int frameY(int nativeY) {
        return frame.y() + Math.round(nativeY * scale);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        graphics.blit(FRAME, frame.x(), frame.y(), frame.width(), frame.height(),
                0, 0, (int) NATIVE_WIDTH, (int) NATIVE_HEIGHT, (int) NATIVE_WIDTH, (int) NATIVE_HEIGHT);

        drawHeading(graphics);
        drawBalance(graphics);
        if (grid != null) drawCells(graphics, mouseX, mouseY, partialTicks);
    }

    private void drawHeading(GuiGraphics graphics) {
        String text = page.pageCount() > 1
                ? page.heading() + "  " + (page.pageIndex() + 1) + "/" + page.pageCount()
                : page.heading();
        graphics.drawCenteredString(font, text,
                heading.x() + heading.width() / 2,
                heading.y() + (heading.height() - font.lineHeight) / 2, 0xFFB8F0FF);
    }

    private void drawBalance(GuiGraphics graphics) {
        graphics.drawCenteredString(font, "RAID POINTS: " + page.balance(),
                balancePill.x() + balancePill.width() / 2,
                balancePill.y() + (balancePill.height() - font.lineHeight) / 2, 0xFFEAF8FF);
    }

    private void drawCells(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int hovered = grid.slotAt(mouseX, mouseY);
        List<ShopEntryPayload> entries = page.entries();

        for (int slot = 0; slot < grid.slotCount(); slot++) {
            int x = grid.cellX(slot);
            int y = grid.cellY(slot);
            int cell = grid.cell();
            ShopEntryPayload entry = slot < entries.size() ? entries.get(slot) : null;

            if (entry == null) {
                graphics.fill(x, y, x + cell, y + cell, 0xDD0E1620);
                continue;
            }
            boolean affordable = page.balance() >= entry.cost() && !entry.owned();
            graphics.fill(x, y, x + cell, y + cell, 0xFF12243A);
            graphics.renderOutline(x, y, cell, cell,
                    slot == hovered ? 0xFF5ADCFF : (affordable ? 0xFF2678B4 : 0xFF23384B));

            drawContents(graphics, entry, x, y, cell, partialTicks);
            // Dim enough to read as unavailable, light enough to still see what it is. At the
            // 0x88 this started on, the item disappeared entirely and the cell looked broken.
            if (!affordable) graphics.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, 0x55070A10);
            drawPrice(graphics, entry, x, y, cell, affordable);
        }
        if (hovered >= 0 && hovered < entries.size()) drawTooltip(graphics, entries.get(hovered), mouseX, mouseY);
    }

    private void drawContents(GuiGraphics graphics, ShopEntryPayload entry, int x, int y, int cell,
                              float partialTicks) {
        if (entry.pokemon()) {
            if (ShopPokemonPortraits.draw(graphics, entry.species(), entry.shiny(), x, y, cell, partialTicks)) {
                return;
            }
            // The fallback exists because a mistyped species must leave a readable cell rather than
            // an empty one an operator cannot diagnose.
            graphics.drawCenteredString(font, entry.species(), x + cell / 2,
                    y + (cell - font.lineHeight) / 2, 0xFF8FB6D6);
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()), entry.count());
        // Items draw at a fixed 16x16, so the stack is scaled into the cell rather than left small.
        float itemScale = grid.itemScale();
        graphics.pose().pushPose();
        graphics.pose().translate(x + (cell - ShopGridLayout.ITEM_PIXELS * itemScale) / 2f,
                y + cell * 0.16f, 0f);
        graphics.pose().scale(itemScale, itemScale, 1f);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
        if (entry.count() > 1) {
            graphics.drawString(font, "x" + entry.count(), x + 3, y + 3, 0xFFCFE6F5, true);
        }
    }

    private void drawPrice(GuiGraphics graphics, ShopEntryPayload entry, int x, int y, int cell,
                           boolean affordable) {
        String price = entry.owned() ? "OWNED" : String.valueOf(entry.cost());
        int colour = entry.owned() ? 0xFF9AE6A0 : (affordable ? 0xFF82EBFF : 0xFFFF8A8A);
        graphics.drawString(font, price, x + cell - font.width(price) - 3,
                y + cell - font.lineHeight - 2, colour, true);
    }

    private void drawTooltip(GuiGraphics graphics, ShopEntryPayload entry, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        if (entry.pokemon()) {
            lines.add(Component.literal((entry.shiny() ? "Shiny " : "") + capitalise(entry.species()))
                    .withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Level " + entry.level()).withStyle(ChatFormatting.GRAY));
        } else {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()), entry.count());
            lines.add(stack.getHoverName());
            lines.add(Component.literal("x" + entry.count()).withStyle(ChatFormatting.GRAY));
        }
        if (entry.owned()) {
            lines.add(Component.literal("Already purchased").withStyle(ChatFormatting.GREEN));
        } else {
            lines.add(Component.literal(entry.cost() + " RP").withStyle(
                    page.balance() >= entry.cost() ? ChatFormatting.AQUA : ChatFormatting.RED));
            if (page.balance() < entry.cost()) {
                lines.add(Component.literal((entry.cost() - page.balance()) + " RP short")
                        .withStyle(ChatFormatting.DARK_RED));
            }
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (page.pageCount() > 1 && leftArrow.contains(mouseX, mouseY)) {
                turn(-1);
                return true;
            }
            if (page.pageCount() > 1 && rightArrow.contains(mouseX, mouseY)) {
                turn(1);
                return true;
            }
            if (grid != null) {
                int slot = grid.slotAt(mouseX, mouseY);
                if (slot >= 0 && slot < page.entries().size()) {
                    ShopEntryPayload entry = page.entries().get(slot);
                    // The click carries the id and the page it was clicked on. The server re-reads
                    // everything else, so a stale page or a hostile client buys nothing unusual.
                    ClientPlayNetworking.send(ShopActionPayload.buy(page.pageIndex(), entry.id()));
                    click();
                    return true;
                }
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
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
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
