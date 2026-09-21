package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.jailmod.JailMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

@Mixin(ServerPlayer.class)
public class PlayerEntityMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void jailmod$preventDrop(ItemStack stack, boolean retainOwnership,
            Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer serverPlayer = (ServerPlayer) (Object) this;
        if (JailMod.isPlayerInJail(serverPlayer)) {
            if (!stack.isEmpty()) {
                serverPlayer.getInventory().add(stack);
                serverPlayer.getInventory().setChanged();
                serverPlayer.containerMenu.broadcastChanges();
                serverPlayer.inventoryMenu.broadcastChanges();
            }
            cir.setReturnValue(null);
        }
    }
}
