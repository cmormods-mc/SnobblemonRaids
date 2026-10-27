package com.cobbleraids.worldboss.arena;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A marker an operator places in the arena. It exists only on the server: a client is told it is a structure void (invisible, no collision,
 * a small outline only when something is aimed at it), so nobody needs a resource pack for it and players walk through it unaware.
 * It cannot be broken in survival (hardness -1); a creative operator breaks it to unregister it.
 *
 * <p>{@link ArenaMarkers.Kind#SPAWN}: where the World Boss stands. {@link ArenaMarkers.Kind#ENTRANCE}: where warping players arrive.
 */
public final class ArenaMarkerBlock extends Block implements PolymerBlock {
    private final ArenaMarkers.Kind kind;

    public ArenaMarkerBlock(Properties properties, ArenaMarkers.Kind kind) {
        super(properties);
        this.kind = kind;
    }

    public ArenaMarkers.Kind kind() {
        return kind;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state) {
        return Blocks.STRUCTURE_VOID.defaultBlockState();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            ArenaMarkers markers = ArenaMarkers.get(server.getServer());
            if (markers.add(kind, new ArenaMarkers.Spot(server.dimension().location().toString(), pos.immutable()))) markers.flush(server.getServer());
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel server && !newState.is(this)) {
            ArenaMarkers markers = ArenaMarkers.get(server.getServer());
            if (markers.remove(kind, new ArenaMarkers.Spot(server.dimension().location().toString(), pos.immutable()))) markers.flush(server.getServer());
        }
    }
}
