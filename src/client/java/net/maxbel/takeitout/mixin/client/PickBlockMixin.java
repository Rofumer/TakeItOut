package net.maxbel.takeitout.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.Util;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
public abstract class PickBlockMixin {
    @Shadow public ClientPlayerEntity player;
    @Shadow @Nullable public ClientWorld world;

    // Перехватываем вызов BlockHitResult#getBlockPos() внутри MinecraftClient#doItemPick()
    @Redirect(
            method = "doItemPick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/hit/BlockHitResult;getBlockPos()Lnet/minecraft/util/math/BlockPos;")
    )
    private BlockPos takeItOut$pickFromShulker(BlockHitResult hit) {
        if (this.world == null || this.player == null) {
            return hit.getBlockPos();
        }

        // Нужный предмет из блока под курсором
        BlockState state = this.world.getBlockState(hit.getBlockPos());
        Block block = state.getBlock();
        ItemStack needed = block.asItem().getDefaultStack();

        PlayerInventory inv = this.player.getInventory();
        if (this.player.getAbilities().creativeMode) {
            return hit.getBlockPos(); // в креативе не трогаем
        }

        // Если предмет уже есть в инвентаре — ничего не делаем
        if (inv.getSlotWithStack(needed) != -1) {
            return hit.getBlockPos();
        }

        // Поищем в шалкере и попросим сервер переложить
        int shulkerSlot = Util.getShulkerWithStack(inv, needed);
        if (shulkerSlot != -1) {
            int inner = Util.getSlotWithStack(
                    ItemStackInventory.getInventoryFromShulker(inv.getStack(shulkerSlot)),
                    needed
            );
            if (inner != -1) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeVarInt(inner);
                buf.writeVarInt(shulkerSlot);
                ClientPlayNetworking.send(Takeitout.GETSTACK, buf);
            }
        }

        return hit.getBlockPos();
    }
}
