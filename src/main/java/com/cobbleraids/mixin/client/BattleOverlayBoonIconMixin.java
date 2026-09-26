package com.cobbleraids.mixin.client;

import com.cobbleraids.client.renown.RenownBoonClientCache;
import com.cobbleraids.client.renown.RenownBoonIcons;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.renown.RenownBoon;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBallDisplay;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
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
 * <p>{@code BattleOverlay.drawBattleTile}'s own {@code displayName} parameter cannot carry the boon:
 * it traces back to {@code ActiveBattlePokemonDTO.Companion.fromPokemon}, which sources it from
 * {@code Pokemon.getDisplayName()} -- Cobblemon's own nickname-or-species text, never the entity's
 * real display name. And the entity's real display name cannot carry it either: see
 * {@link RenownBoonClientCache} for why a would-be marker riding on it never survives
 * {@code PokemonEntity.setCustomName}. The boon instead reaches the client over a dedicated packet
 * ({@link com.cobbleraids.network.RenownBoonSyncPayload}), keyed by the Pokemon's own UUID.
 *
 * <p>This mixin hooks {@code drawTile} (which still has the real {@link ActiveClientBattlePokemon},
 * and so the {@link ClientBattlePokemon}'s UUID) to resolve that UUID against the cache and stash
 * the result for the very next {@code drawBattleTile} call on the render thread to pick up.
 * {@code drawTile} is {@code drawBattleTile}'s only caller in this class (both also have Kotlin
 * default-arg {@code $default} wrappers that just forward into the same two methods), so the
 * hand-off is safe without needing a queue or per-tile keying.
 *
 * <p>The badge draws at {@code drawBattleTile}'s TAIL, not HEAD: the method paints its own
 * background/portrait/border after its body starts, so a badge injected at HEAD would be
 * immediately painted over by the tile's own visuals. The method has exactly one {@code return},
 * so TAIL fires once, after everything else, with the badge landing on top.
 *
 * <p>Placed just outside the tile's own edge, on the side away from the portrait, rather than
 * inside the ~7px gap between the portrait and where the name text starts ({@code infoOffsetX} in
 * {@code drawBattleTile}'s own layout math): that gap is nowhere near wide enough for a full-size
 * icon without either overlapping the portrait or overlapping the name itself once it runs past a
 * few characters, which is exactly what a renowned boss's longer title did. No backing plate --
 * a flat colour square read as a UI glitch sitting over the tile rather than a badge next to it.
 */
@Mixin(BattleOverlay.class)
public abstract class BattleOverlayBoonIconMixin {
    private static Optional<RenownBoon> cobbleRaids$pendingBoon = Optional.empty();

    @Inject(method = "drawTile", at = @At("HEAD"))
    private void cobbleRaids$resolveBoon(GuiGraphics context, float x, ActiveClientBattlePokemon activeBattlePokemon,
                                          boolean reversed, int compactIndex, PokedexEntryProgress dexState,
                                          boolean isSelected, boolean isHovered, boolean isFlatHealth,
                                          CallbackInfo ci) {
        try {
            ClientBattlePokemon battlePokemon = activeBattlePokemon.getBattlePokemon();
            cobbleRaids$pendingBoon = battlePokemon == null
                    ? Optional.empty()
                    : RenownBoonClientCache.forPokemonUuid(battlePokemon.getUuid());
        } catch (Exception ex) {
            cobbleRaids$pendingBoon = Optional.empty();
            RaidFaultBarrier.report("mixin:drawTile", ex);
        }
    }

    @Inject(method = "drawBattleTile", at = @At("TAIL"))
    private void cobbleRaids$boonIcon(GuiGraphics context, float x, float y, float partialTicks, boolean reversed,
                                       Species species, int level, MutableComponent displayName, Gender gender,
                                       PersistentStatus status, PosableState state, Triple<Float, Float, Float> colour,
                                       float opacity, ClientBallDisplay ballState, int maxHealth, float health,
                                       boolean isSelected, boolean isHovered, boolean isCompact,
                                       MutableComponent actorDisplayName, boolean isFlatHealth,
                                       PokedexEntryProgress dexState, CallbackInfo ci) {
        try {
            Optional<RenownBoon> boon = cobbleRaids$pendingBoon;
            if (boon.isEmpty()) return;
            ResourceLocation icon = RenownBoonIcons.iconFor(boon.get());
            if (icon == null) return;

            int tileWidth = isCompact ? 128 : 140;
            int size = 16;
            int gap = 3;
            int nameY = Math.round(y + (isCompact ? 5 : 7));
            // Outside the tile entirely -- to the left of it when the portrait is on the right
            // (reversed), to the right of it when the portrait is on the left -- so a long title
            // never runs into the badge no matter how far the name text extends.
            int badgeX = Math.round(reversed ? x - size - gap : x + tileWidth + gap);
            int badgeY = nameY - 1;

            context.setColor(1f, 1f, 1f, opacity);
            context.blit(icon, badgeX, badgeY, size, size, 0f, 0f, 16, 16, 16, 16);
            context.setColor(1f, 1f, 1f, 1f);
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:drawBattleTile", ex);
        }
    }
}
