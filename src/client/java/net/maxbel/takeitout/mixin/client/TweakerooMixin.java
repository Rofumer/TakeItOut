package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.tweakeroo.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.Util;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(InventoryUtils.class)
public class TweakerooMixin {

    /**
     * restockNewStackToHand(PlayerEntity player, Hand hand, ItemStack stackReference, boolean allowHotbar)
     * Захватываем локалку slotWithItem.
     */
    @Inject(
            method = "restockNewStackToHand",
            at = @At("TAIL"),
            cancellable = false,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private static void takeItOut$restockTail(PlayerEntity player,
                                              Hand hand,
                                              ItemStack stackReference,
                                              boolean allowHotbar,
                                              CallbackInfo ci,
                                              int slotWithItem) {
        // если Tweakeroo не нашёл замену в инвентаре — пробуем достать из шалкера
        if (slotWithItem == -1) {
            int shulker = Util.getShulkerWithStack(player.getInventory(), stackReference);
            if (shulker != -1) {
                int inner = Util.getSlotWithStack(
                        ItemStackInventory.getInventoryFromShulker(player.getInventory().getStack(shulker)),
                        stackReference
                );
                if (inner != -1) {
                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeVarInt(inner);
                    buf.writeVarInt(shulker);
                    ClientPlayNetworking.send(Takeitout.GETSTACK, buf);
                }
            }
        }
    }
}
