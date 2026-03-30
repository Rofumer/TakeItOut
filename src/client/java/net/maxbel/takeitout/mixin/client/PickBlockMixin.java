package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class PickBlockMixin {

    @Shadow public LocalPlayer player;
    @Shadow @Nullable public ClientLevel level;

    @Redirect(
            method = "pickBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/BlockHitResult;getBlockPos()Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos pickFromShulker(BlockHitResult instance) {

        if (level == null || player == null) {
            return instance.getBlockPos();
        }

        BlockState blockState = level.getBlockState(instance.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack stack = block.asItem().getDefaultInstance();

        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(stack);

        // creative → не вмешиваемся
        if (player.getAbilities().instabuild) {
            return instance.getBlockPos();
        }

        if (slot != -1) {
            return instance.getBlockPos();
        }

        int shulker = Util.getShulkerWithStack(inventory, stack);
        if (shulker != -1) {

            int inner = Util.getSlotWithStack(
                    ItemStackInventory.getInventoryFromShulker(inventory.getItem(shulker)),
                    stack
            );

            if (inner != -1) {
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulker));
            }
        }

        return instance.getBlockPos();
    }
}