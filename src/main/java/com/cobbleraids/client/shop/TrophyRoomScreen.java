package com.cobbleraids.client.shop;

import com.cobbleraids.client.gui.RaidGuiLayout;
import com.cobbleraids.client.gui.RaidGuiSkin;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.network.TrophyRoomActionPayload;
import com.cobbleraids.network.TrophyRoomEntryPayload;
import com.cobbleraids.network.TrophyRoomPagePayload;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * A permanent, browsable record of every species a player has ever defeated in a raid.
 *
 * <p>A plain {@link Screen} reusing the shop's own chrome ({@link RaidGuiLayout}/{@link RaidGuiSkin})
 * and species-icon lookup ({@link ShopSpeciesIcons}), the same way {@link RaidShopScreen} does --
 * lives in this package rather than a new one specifically to reach those two package-private
 * helpers without widening their visibility for a second caller. Unlike the shop there is nothing to
 * buy: a click does nothing but show a tooltip, and the only interaction is turning pages.
 */
public final class TrophyRoomScreen extends Screen {

    private static final ResourceLocation FALLBACK_ICON =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static TrophyRoomScreen open;

    private TrophyRoomPagePayload page;
    private RaidGuiLayout.Layout layout;
    private ItemStack fallbackIcon = ItemStack.EMPTY;
    private int tooltipSlot = -1;
    private List<Component> tooltipLines = List.of();

    private TrophyRoomScreen(TrophyRoomPagePayload page) {
        super(Component.literal("Trophy Room"));
        setPage(page);
    }

    private void setPage(TrophyRoomPagePayload payload) {
        this.page = payload;
        this.tooltipSlot = -1;
        relayout();
        if (fallbackIcon.isEmpty()) {
            fallbackIcon = new ItemStack(BuiltInRegistries.ITEM.get(FALLBACK_ICON));
        }
    }

    /** Opens the screen, or refreshes the one already open. Called from the payload receiver. */
    public static void show(TrophyRoomPagePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (open != null && client.screen == open) {
            open.setPage(payload);
            return;
        }
        open = new TrophyRoomScreen(payload);
        client.setScreen(open);
    }

    /** See {@link RaidShopScreen#removed()} for why cleanup lives here rather than in onClose(). */
    @Override
    public void removed() {
        if (open == this) open = null;
        tooltipLines = List.of();
        ShopPokemonPortraits.clear();
        super.removed();
    }

    @Override
    protected void init() {
        relayout();
    }

    private void relayout() {
        layout = RaidGuiLayout.fit(width, height, RaidGuiLayout.POKEMON_COLUMNS, RaidGuiLayout.POKEMON_ROWS)
                .orElse(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        if (layout == null) {
            graphics.drawCenteredString(font, "Not enough room to show the trophy room.",
                    width / 2, height / 2, 0xFFFF8A8A);
            return;
        }
        RaidGuiSkin.renderChrome(graphics, layout);
        if (page.pageCount() > 1) RaidGuiSkin.renderArrows(graphics, layout);

        drawHeading(graphics);
        drawCells(graphics, mouseX, mouseY, partialTicks);
    }

    private void drawHeading(GuiGraphics graphics) {
        RaidGuiLayout.Rect frame = layout.frame();
        String heading = page.pageCount() > 1
                ? "Trophy Room " + (page.pageIndex() + 1) + "/" + page.pageCount()
                : "Trophy Room";
        graphics.drawCenteredString(font, heading, frame.x() + frame.width() / 2, frame.y() + 24, 0xFFAFFFFF);
    }

    private void drawCells(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int hovered = layout.slotAt(mouseX, mouseY);
        List<TrophyRoomEntryPayload> entries = page.entries();
        List<RaidGuiLayout.Rect> slots = layout.slots();

        for (int slot = 0; slot < slots.size() && slot < entries.size(); slot++) {
            RaidGuiLayout.Rect rect = slots.get(slot);
            TrophyRoomEntryPayload entry = entries.get(slot);

            drawSpecies(graphics, entry, rect, partialTicks);
            drawTimesDefeated(graphics, entry, rect);
            if (slot == hovered) {
                graphics.fill(rect.x(), rect.y(), rect.x() + rect.width(),
                        rect.y() + rect.height(), 0x40FFFFFF);
            }
        }
        if (hovered >= 0 && hovered < entries.size()) {
            if (hovered != tooltipSlot) {
                tooltipSlot = hovered;
                tooltipLines = tooltipFor(entries.get(hovered));
            }
            graphics.renderComponentTooltip(font, tooltipLines, mouseX, mouseY);
        } else {
            tooltipSlot = -1;
        }
    }

    private void drawSpecies(GuiGraphics graphics, TrophyRoomEntryPayload entry,
                             RaidGuiLayout.Rect rect, float partialTicks) {
        if (drawSpeciesIcon(graphics, entry, rect)) return;
        if (ShopPokemonPortraits.draw(graphics, entry.species(), entry.shiny(),
                rect.x(), rect.y(), rect.width(), partialTicks)) {
            return;
        }
        graphics.renderItem(fallbackIcon, RaidGuiSkin.itemX(rect), RaidGuiSkin.itemY(rect));
    }

    /** See {@link RaidShopScreen#drawSpeciesIcon} -- same lookup, same fit-by-narrower-ratio scaling. */
    private boolean drawSpeciesIcon(GuiGraphics graphics, TrophyRoomEntryPayload entry, RaidGuiLayout.Rect rect) {
        ResourceLocation texture = ShopSpeciesIcons.texture(entry.species(), entry.shiny());
        if (texture == null) return false;
        int cell = rect.width();
        float scale = Math.min(cell / (float) ShopSpeciesIcons.WIDTH, cell / (float) ShopSpeciesIcons.HEIGHT);
        int width = Math.max(1, Math.round(ShopSpeciesIcons.WIDTH * scale));
        int height = Math.max(1, Math.round(ShopSpeciesIcons.HEIGHT * scale));
        graphics.blit(texture,
                rect.x() + (cell - width) / 2, rect.y() + (cell - height) / 2,
                width, height, 0.0F, 0.0F,
                ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT,
                ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT);
        return true;
    }

    /** How many times this species has been defeated, in the cell's top-left corner. */
    private void drawTimesDefeated(GuiGraphics graphics, TrophyRoomEntryPayload entry, RaidGuiLayout.Rect rect) {
        graphics.drawString(font, "x" + entry.timesDefeated(), rect.x() + 1, rect.y() + 1, 0xFFB8D8EA, true);
    }

    private List<Component> tooltipFor(TrophyRoomEntryPayload entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal((entry.shiny() ? "Shiny " : "") + capitalise(entry.species()))
                .withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal("Level " + entry.level()).withStyle(ChatFormatting.GRAY));
        RaidRarityTier tier = parseTier(entry.tier());
        if (tier != null) {
            lines.add(Component.literal(tier.displayName()).withStyle(RaidTierPresentation.color(tier)));
        }
        lines.add(Component.literal("IV: " + entry.ivPercent() + "%").withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("EV: " + entry.evPercent() + "%").withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("First defeated: " + DATE_FORMAT.format(Instant.ofEpochMilli(entry.firstDefeatedAtEpochMs())))
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Defeated " + entry.timesDefeated() + " time"
                        + (entry.timesDefeated() == 1 ? "" : "s"))
                .withStyle(ChatFormatting.GRAY));
        return List.copyOf(lines);
    }

    private static RaidRarityTier parseTier(String value) {
        try {
            return RaidRarityTier.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
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
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void turn(int direction) {
        int next = Math.floorMod(page.pageIndex() + direction, Math.max(1, page.pageCount()));
        ClientPlayNetworking.send(new TrophyRoomActionPayload(next));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
