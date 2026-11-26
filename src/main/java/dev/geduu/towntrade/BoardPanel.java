package dev.geduu.towntrade;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

class BoardPanel extends Block {
    static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    static final double DEPTH = 2.0 / 16;
    private static final MapCodec<BoardPanel> CODEC = simpleCodec(BoardPanel::new);
    private static final VoxelShape NORTH = box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SOUTH = box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape EAST = box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape WEST = box(14, 0, 0, 16, 16, 16);

    BoardPanel(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var facing = context.getClickedFace();
        return facing.getAxis().isHorizontal() ? defaultBlockState().setValue(FACING, facing) : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        var facing = state.getValue(FACING);
        if (!state.is(TradeBoard.PANEL) || !player.isShiftKeyDown() || hit.getDirection() != facing) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
            TradeBoard.INSTANCE.select(serverPlayer, serverLevel, pos, facing);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            default -> WEST;
        };
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean piston) {
        super.affectNeighborsAfterRemoval(state, level, pos, piston);
        if (state.is(TradeBoard.PANEL) && !level.getBlockState(pos).is(TradeBoard.CONTROLLER)
                && !TownTrade.isReplayServer(level.getServer())) {
            var facing = state.getValue(FACING);
            var lowerLeft = pos.relative(right(facing), 1 - TradeBoard.MAX_WIDTH).below(TradeBoard.MAX_HEIGHT - 1);
            for (var anchor : BlockPos.betweenClosed(lowerLeft, pos)) {
                if (level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof TradeBoardEntity board
                        && board.facing() == facing && board.contains(pos)) {
                    board.disband(level);
                    return;
                }
            }
        }
    }

    static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    static int across(BlockPos start, BlockPos end, Direction facing) {
        var right = right(facing);
        return (end.getX() - start.getX()) * right.getStepX() + (end.getZ() - start.getZ()) * right.getStepZ();
    }

    static boolean aligned(BlockPos start, BlockPos end, Direction facing) {
        return facing.getAxis().choose(start.getX(), start.getY(), start.getZ())
                == facing.getAxis().choose(end.getX(), end.getY(), end.getZ());
    }

    static final class Controller extends BoardPanel implements EntityBlock {
        private static final MapCodec<Controller> CODEC = simpleCodec(Controller::new);

        Controller(Properties properties) { super(properties); }

        @Override
        protected MapCodec<? extends Block> codec() { return CODEC; }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new TradeBoardEntity(pos, state);
        }

        @Override
        protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
            return new ItemStack(TradeBoard.ITEM);
        }
    }
}
