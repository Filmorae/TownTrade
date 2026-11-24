package dev.geduu.towntrade.mixin;

import dev.geduu.towntrade.PlayerTransactionTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerTransactionTag implements PlayerTransactionTag {
    @Unique private String towntrade$marker = "";
    @Unique private boolean towntrade$permitFlight;
    @Unique private long towntrade$landing;
    @Unique private boolean towntrade$restoredFlying;

    @Override public boolean towntrade$permitFlight() { return towntrade$permitFlight; }
    @Override public void towntrade$setPermitFlight(boolean owned) { towntrade$permitFlight = owned; }
    @Override public boolean towntrade$restoredFlying() { return towntrade$restoredFlying; }
    @Override public void towntrade$setRestoredFlying(boolean flying) { towntrade$restoredFlying = flying; }
    @Override public long towntrade$landing() { return towntrade$landing; }
    @Override public void towntrade$setLanding(long until) { towntrade$landing = until; }

    @Override
    public String towntrade$transaction() {
        return towntrade$marker;
    }

    @Override
    public void towntrade$setTransaction(String transaction) {
        towntrade$marker = transaction;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void towntrade$write(ValueOutput output, CallbackInfo callback) {
        if (!towntrade$marker.isEmpty()) output.putString(KEY, towntrade$marker);
        if (towntrade$permitFlight) {
            output.putBoolean("TownTradePermitFlight", true);
            var player = (ServerPlayer) (Object) this;
            output.putBoolean("TownTradeWasFlying", player.getAbilities().flying || towntrade$restoredFlying);
        }
        if (towntrade$landing != 0) output.putLong("TownTradeLanding", towntrade$landing);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void towntrade$read(ValueInput input, CallbackInfo callback) {
        towntrade$marker = input.getStringOr(KEY, "");
        towntrade$permitFlight = input.getBooleanOr("TownTradePermitFlight", false);
        towntrade$restoredFlying = towntrade$permitFlight && input.getBooleanOr("TownTradeWasFlying", true);
        towntrade$landing = input.getLongOr("TownTradeLanding", 0);
    }

    @Inject(method = "checkFallDamage", at = @At("TAIL"))
    private void towntrade$finishLanding(double dy, boolean grounded, BlockState state, BlockPos position,
                                         CallbackInfo callback) {
        if (grounded) towntrade$landing = 0;
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void towntrade$copy(ServerPlayer previous, boolean keepEverything, CallbackInfo callback) {
        var data = (PlayerTransactionTag) previous;
        towntrade$marker = data.towntrade$transaction();
        towntrade$permitFlight = data.towntrade$permitFlight();
        towntrade$landing = keepEverything ? data.towntrade$landing() : 0;
    }
}
