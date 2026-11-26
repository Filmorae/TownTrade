package dev.geduu.towntrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

final class TradeBoardEntity extends BlockEntity {
    private int width;
    private int height;
    private boolean checked;
    private BoardSnapshot snapshot = BoardSnapshot.STARTING;

    TradeBoardEntity(BlockPos pos, BlockState state) {
        super(TradeBoard.ENTITY_TYPE, pos, state);
    }

    int width() { return width; }
    int height() { return height; }
    boolean formed() { return TradeBoard.validSize(width, height); }
    Direction facing() { return getBlockState().getValue(BoardPanel.FACING); }
    BoardSnapshot snapshot() { return snapshot; }

    BlockPos end() {
        return worldPosition.relative(BoardPanel.right(facing()), width - 1).above(height - 1);
    }

    AABB bounds() { return AABB.encapsulatingFullBlocks(worldPosition, end()); }

    boolean contains(BlockPos pos) {
        int x = BoardPanel.across(worldPosition, pos, facing());
        int y = pos.getY() - worldPosition.getY();
        return x >= 0 && x < width && y >= 0 && y < height && BoardPanel.aligned(worldPosition, pos, facing());
    }

    boolean intersects(BlockPos start, BlockPos end) {
        var facing = facing();
        int begin = BoardPanel.across(worldPosition, start, facing);
        int finish = BoardPanel.across(worldPosition, end, facing);
        return finish >= 0 && begin < width && end.getY() >= worldPosition.getY()
                && start.getY() < worldPosition.getY() + height;
    }

    void form(int width, int height) {
        this.width = width;
        this.height = height;
        checked = true;
        setChanged();
        ((ServerLevel) level).getChunkSource().blockChanged(worldPosition);
    }

    boolean checkStructure(ServerLevel level) {
        if (!formed()) {
            disband(level);
            return false;
        }
        if (!level.hasChunkAt(end())) {
            checked = false;
            return false;
        }
        if (checked) return true;
        for (var pos : BlockPos.betweenClosed(worldPosition, end())) {
            if (pos.equals(worldPosition)) continue;
            var state = level.getBlockState(pos);
            if (!state.is(TradeBoard.PANEL) || state.getValue(BoardPanel.FACING) != facing()) {
                disband(level);
                return false;
            }
        }
        checked = true;
        return true;
    }

    void disband(ServerLevel level) {
        level.setBlockAndUpdate(worldPosition, TradeBoard.PANEL.defaultBlockState().setValue(BoardPanel.FACING, facing()));
    }

    void publish(ServerLevel level, BoardSnapshot next) {
        if (snapshot.equals(next)) return;
        snapshot = next;
        setChanged();
        level.getChunkSource().blockChanged(worldPosition);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("width", width);
        output.putInt("height", height);
        output.store("public_board", BoardSnapshot.CODEC, snapshot);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        width = input.getIntOr("width", 0);
        height = input.getIntOr("height", 0);
        snapshot = input.read("public_board", BoardSnapshot.CODEC).orElse(BoardSnapshot.STARTING);
        checked = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
