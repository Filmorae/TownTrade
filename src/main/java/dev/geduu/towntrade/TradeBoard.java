package dev.geduu.towntrade;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;

final class TradeBoard {
    static final TradeBoard INSTANCE = new TradeBoard();
    static final int MAX_WIDTH = 9;
    static final int MAX_HEIGHT = 6;
    static final int RANGE = 64;
    static final Identifier PANEL_ID = id("board_panel");
    private static final Identifier CONTROLLER_ID = id("board_controller");

    static BoardPanel PANEL;
    static BoardPanel.Controller CONTROLLER;
    static Item ITEM;
    static BlockEntityType<TradeBoardEntity> ENTITY_TYPE;

    private record Corner(ServerLevel level, BlockPos pos, Direction facing, long deadline) {}

    private final Set<TradeBoardEntity> loaded = new HashSet<>();
    private final Map<ServerPlayer, Corner> selections = new WeakHashMap<>();
    private long nextRefresh;

    private TradeBoard() {}

    void register() {
        var panelKey = ResourceKey.create(Registries.BLOCK, PANEL_ID);
        PANEL = Registry.register(BuiltInRegistries.BLOCK, panelKey, new BoardPanel(properties(panelKey)));
        var controllerKey = ResourceKey.create(Registries.BLOCK, CONTROLLER_ID);
        CONTROLLER = Registry.register(BuiltInRegistries.BLOCK, controllerKey, new BoardPanel.Controller(properties(controllerKey)));
        var itemKey = ResourceKey.create(Registries.ITEM, PANEL_ID);
        ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(PANEL, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()) {
                    @Override
                    public InteractionResult useOn(UseOnContext context) {
                        var player = context.getPlayer();
                        var state = context.getLevel().getBlockState(context.getClickedPos());
                        if (player == null || !player.isShiftKeyDown() || !state.is(PANEL)
                                || state.getValue(BoardPanel.FACING) != context.getClickedFace()) {
                            return super.useOn(context);
                        }
                        if (player instanceof ServerPlayer serverPlayer && context.getLevel() instanceof ServerLevel level) {
                            select(serverPlayer, level, context.getClickedPos(), context.getClickedFace());
                        }
                        return InteractionResult.SUCCESS;
                    }
                });
        ENTITY_TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, CONTROLLER_ID,
                FabricBlockEntityTypeBuilder.create(TradeBoardEntity::new, CONTROLLER).build());
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks"))).register(output -> output.accept(ITEM));
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof TradeBoardEntity board && !TownTrade.isReplayServer(level.getServer())) {
                loaded.add(board);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof TradeBoardEntity board) loaded.remove(board);
        });
    }

    private static BlockBehaviour.Properties properties(ResourceKey<Block> key) {
        return BlockBehaviour.Properties.of().setId(key).strength(1.5F).noOcclusion().pushReaction(PushReaction.BLOCK);
    }

    void select(ServerPlayer player, ServerLevel level, BlockPos pos, Direction facing) {
        if (!player.mayBuild() || !level.mayInteract(player, pos) || TownTrade.isReplayServer(level.getServer())) return;
        var first = selections.remove(player);
        long now = System.currentTimeMillis();
        if (first == null || first.level() != level || first.facing() != facing || first.deadline() < now
                || !BoardPanel.aligned(first.pos(), pos, facing) || first.pos().equals(pos)) {
            selections.put(player, new Corner(level, pos.immutable(), facing, now + TradeRules.MINUTE));
            message(player, "select_other");
            return;
        }
        int across = BoardPanel.across(first.pos(), pos, facing);
        int rise = pos.getY() - first.pos().getY();
        int width = Math.abs(across) + 1;
        int height = Math.abs(rise) + 1;
        if (!validSize(width, height)) {
            message(player, "bad_size");
            return;
        }

        var anchor = first.pos().relative(BoardPanel.right(facing), Math.min(0, across)).above(Math.min(0, rise));
        var end = anchor.relative(BoardPanel.right(facing), width - 1).above(height - 1);
        var leftEdge = anchor.relative(BoardPanel.right(facing), 1 - MAX_WIDTH);
        if (!level.hasChunkAt(leftEdge) || !level.hasChunkAt(end)) {
            message(player, "chunks");
            return;
        }
        for (var tile : BlockPos.betweenClosed(anchor, end)) {
            var state = level.getBlockState(tile);
            if (!state.is(PANEL) || state.getValue(BoardPanel.FACING) != facing || !level.mayInteract(player, tile)) {
                message(player, "rectangle");
                return;
            }
        }
        for (var otherAnchor : BlockPos.betweenClosed(leftEdge.below(MAX_HEIGHT - 1), end)) {
            if (level.getBlockEntity(otherAnchor) instanceof TradeBoardEntity other && other.facing() == facing
                    && other.intersects(anchor, end)) {
                message(player, "overlap");
                return;
            }
        }
        level.setBlockAndUpdate(anchor, CONTROLLER.defaultBlockState().setValue(BoardPanel.FACING, facing));
        if (level.getBlockEntity(anchor) instanceof TradeBoardEntity board) {
            board.form(width, height);
            loaded.add(board);
            nextRefresh = 0;
            message(player, "formed", width, height);
        }
    }

    static boolean validSize(int width, int height) {
        return width >= 3 && width <= MAX_WIDTH && height >= 2 && height <= MAX_HEIGHT
                && width * 3 >= height * 4 && width <= height * 2;
    }

    void tick(MinecraftServer server, TradeService service) {
        if (loaded.isEmpty() || server.getPlayerList().getPlayerCount() == 0) return;
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        nextRefresh = now + 1000;
        BoardSnapshot snapshot = null;
        for (var board : Set.copyOf(loaded)) {
            if (board.isRemoved() || !(board.getLevel() instanceof ServerLevel level)) continue;
            if (!board.checkStructure(level) || !hasViewer(level, board)) continue;
            if (snapshot == null) snapshot = service == null ? BoardSnapshot.OFFLINE : service.publicBoard(now);
            board.publish(level, snapshot);
        }
    }

    private static boolean hasViewer(ServerLevel level, TradeBoardEntity board) {
        var center = Vec3.atCenterOf(board.getBlockPos()).lerp(Vec3.atCenterOf(board.end()), 0.5);
        for (var player : PlayerLookup.tracking(level, board.getBlockPos())) {
            if (player.distanceToSqr(center) <= RANGE * RANGE) return true;
        }
        return false;
    }

    void clear() {
        loaded.clear();
        selections.clear();
        nextRefresh = 0;
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("towntrade", path);
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable("towntrade.board." + key, args), true);
    }
}
