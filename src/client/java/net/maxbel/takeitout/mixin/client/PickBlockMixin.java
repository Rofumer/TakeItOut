package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.maxbel.takeitout.client.*;

@Mixin(value = {MinecraftClient.class})
public abstract class PickBlockMixin {
    @Shadow
    public ClientPlayerEntity player;

    @Shadow @Nullable public ClientWorld world;

    @Shadow @Nullable public HitResult crosshairTarget;

    @Inject(
            method = {"doItemPick"},
            at = @At("HEAD"),
            require = 0
    )
    private void takeitout$pickBlock(CallbackInfo ci) {
        if (this.world == null || this.player == null) return;
        if (this.player.getAbilities().creativeMode) return;
        if (!(this.crosshairTarget instanceof BlockHitResult blockHitResult)) return;

        BlockState blockState = this.world.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack stack = block.asItem().getDefaultStack();

        PlayerInventory inventory = this.player.getInventory();
        if (inventory.getSlotWithStack(stack) != -1) return;

        int shulker = Util.getShulkerWithStack(inventory, stack);
        if (shulker != -1) {
            int inner = Util.getSlotWithStack(
                    ItemStackInventory.getInventoryFromShulker(inventory.getStack(shulker)),
                    stack
            );
            if (inner != -1) {
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulker, TakeitoutClient.TAKE_SINGLE_ITEM_MODE));
            }
        } else {
            WorldContainerSources.requestStack(MinecraftClient.getInstance(), stack, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
        }
    }
}
