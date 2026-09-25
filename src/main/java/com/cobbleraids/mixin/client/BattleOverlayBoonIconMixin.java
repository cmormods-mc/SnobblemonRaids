package com.cobbleraids.mixin.client;

import com.cobbleraids.client.renown.RenownBoonIcons;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.renown.RenownBoon;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.client.battle.ClientBallDisplay;
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.status.PersistentStatus;
import java.util.Optional;
import kotlin.Triple;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Badges a renowned boss's in-battle tile (the top-corner box with its portrait, name and HP bar)
 * with the same boon icon as the world nameplate and the reward reveal screen.
 *
 * <p>{@code BattleOverlay.drawBattleTile} draws this tile from its own parameters, not from the
 * entity directly, but {@code displayName} is the same {@link MutableComponent} tree
 * {@link RaidBossNameplate#of} builds -- Cobblemon derives a wild battle participant's battle-tile
 * name from the entity's own display name, so the boon marker tucked into the epithet's style rides
 * along here too, with nothing extra to wire up.
 *
 * <p>Placed as a small corner badge rather than inline with the name: the tile is already narrow
 * enough that a renowned title truncates (see the "Soren, the Dread Arc..." box this was built to
 * fix), so an icon squeezed next to the text would only make that worse. The corner opposite the
 * portrait circle is unused space in every layout this method draws.
 */
@Mixin(BattleOverlay.class)
public abstract class BattleOverlayBoonIconMixin {
    @Inject(method = "drawBattleTile", at = @At("HEAD"))
    private void cobbleRaids$boonIcon(GuiGraphics context, float x, float y, float partialTicks, boolean reversed,
                                       Species species, int level, MutableComponent displayName, Gender gender,
                                       PersistentStatus status, PosableState state, Triple<Float, Float, Float> colour,
                                       float opacity, ClientBallDisplay ballState, int maxHealth, float health,
                                       boolean isSelected, boolean isHovered, boolean isCompact,
                                       MutableComponent actorDisplayName, boolean isFlatHealth,
                                       PokedexEntryProgress dexState, CallbackInfo ci) {
        try {
            Optional<RenownBoon> boon = RaidBossNameplate.readBoonMarker(displayName);
            if (boon.isEmpty()) return;
            ResourceLocation icon = RenownBoonIcons.iconFor(boon.get());
            if (icon == null) return;

            int tileWidth = isCompact ? 128 : 140;
            int size = 10;
            int badgeX = Math.round(reversed ? x + 2 : x + tileWidth - size - 2);
            int badgeY = Math.round(y + 1);

            context.setColor(1f, 1f, 1f, opacity);
            context.blit(icon, badgeX, badgeY, size, size, 0f, 0f, 16, 16, 16, 16);
            context.setColor(1f, 1f, 1f, 1f);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:drawBattleTile", ex);
        }
    }
}
