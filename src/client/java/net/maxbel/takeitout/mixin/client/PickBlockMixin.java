package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.maxbel.takeitout.client.*;

@Mixin(value = {MinecraftClient.class})
public abstract class PickBlockMixin {
    @Shadow
    public ClientPlayerEntity player;

    @Redirect(method = {"doItemPick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;getSlotWithStack(Lnet/minecraft/item/ItemStack;)I"))

    public int pickFromShulker(PlayerInventory playerInventory, ItemStack stack) {
        if (this.player.getAbilities().creativeMode) {
            return 0;
        }
        int slot = playerInventory.getSlotWithStack(stack);
        if (slot != -1) {
            return slot;
        }
        int shulker = Util.getShulkerWithStack(playerInventory, stack);
        if (shulker != -1) {
            slot = Util.getSlotWithStack(ItemStackInventory.getInventoryFromShulker(this.player.getInventory().getStack(shulker)), stack);
            if (slot != -1) {
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
            }
        }
        return -1;
    }
}
