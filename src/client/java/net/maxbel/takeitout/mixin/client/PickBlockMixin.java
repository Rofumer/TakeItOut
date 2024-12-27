package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.maxbel.takeitout.client.*;

@Mixin(value = {MinecraftClient.class})
public abstract class PickBlockMixin {
    @Shadow
    public ClientPlayerEntity player;

    @Shadow @Nullable public ClientWorld world;

    //@Redirect(method = {"doItemPick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;getSlotWithStack(Lnet/minecraft/item/ItemStack;)I"))
    @Redirect(method = {"doItemPick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/hit/BlockHitResult;getBlockPos()Lnet/minecraft/util/math/BlockPos;"))

    //public int pickFromShulker(PlayerInventory playerInventory, ItemStack stack) {
    public BlockPos pickFromShulker(BlockHitResult instance) {

        ItemStack stack;

        BlockState blockState =  this.world.getBlockState(instance.getBlockPos());
        Block block = blockState.getBlock();
        stack = block.asItem().getDefaultStack();

        PlayerInventory playerInventory = this.player.getInventory();
        int slot = playerInventory.getSlotWithStack(stack);

        if (this.player.getAbilities().creativeMode) {
            return instance.getBlockPos();
        }

        if (slot != -1) {
            return instance.getBlockPos();
        }
        int shulker = Util.getShulkerWithStack(playerInventory, stack);
        if (shulker != -1) {
            slot = Util.getSlotWithStack(ItemStackInventory.getInventoryFromShulker(this.player.getInventory().getStack(shulker)), stack);
            if (slot != -1) {
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
            }
        }

        return instance.getBlockPos();
    }
}
