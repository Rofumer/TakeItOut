package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.Util;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class PickBlockMixin {

    @Shadow public LocalPlayer player;
    @Shadow @Nullable public ClientLevel level;
    @Shadow @Nullable public HitResult hitResult;

    @Inject(
            method = "pickBlockOrEntity",
            at = @At("HEAD"),
            require = 0
    )
    private void takeitout$pickBlock(CallbackInfo ci) {
        if (level == null || player == null) return;
        if (player.getAbilities().instabuild) return;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockState blockState = level.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack stack = block.asItem().getDefaultInstance();

        Inventory inventory = player.getInventory();
        if (inventory.findSlotMatchingItem(stack) != -1) return;

        int shulker = Util.getShulkerWithStack(inventory, stack);
        if (shulker != -1) {
            int inner = Util.getSlotWithStack(
                    ItemStackInventory.getInventoryFromShulker(inventory.getItem(shulker)),
                    stack
            );
            if (inner != -1) {
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulker, TakeitoutClient.TAKE_SINGLE_ITEM_MODE));
            }
        } else {
            WorldContainerSources.requestStack(Minecraft.getInstance(), stack, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
        }
    }
}
