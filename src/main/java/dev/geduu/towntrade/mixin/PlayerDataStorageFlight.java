package dev.geduu.towntrade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.geduu.towntrade.PlayerTransactionTag;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageFlight {
    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V"))
    private void towntrade$saveWithoutTemporaryFlight(Player player, ValueOutput output, Operation<Void> save) {
        save.call(player, output);
        if (!(player instanceof PlayerTransactionTag tag) || !tag.towntrade$permitFlight()) return;
        // A permit is restored from the Town Trade ledger, not from vanilla's saved flight bits.
        var abilities = player.getAbilities().pack();
        output.store("abilities", Abilities.Packed.CODEC, new Abilities.Packed(abilities.invulnerable(),
                false, false, abilities.instabuild(), abilities.mayBuild(), abilities.flyingSpeed(), abilities.walkingSpeed()));
    }
}
