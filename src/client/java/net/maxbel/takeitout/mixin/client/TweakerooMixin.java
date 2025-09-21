package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.tweakeroo.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.Util;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

// ваши util’ы

@Mixin(InventoryUtils.class)
public class TweakerooMixin {

    /**
     * restockNewStackToHand(PlayerEntity player, Hand hand, ItemStack stackReference, boolean allowHotbar) : void
     * Локалка slotWithItem есть внутри метода — захватываем её.
     */
    @Inject(
            method = "restockNewStackToHand",
            at = @At("TAIL"),
            cancellable = false,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private static void restockNewStackToHand_tail(PlayerEntity player,
                                                   Hand hand,
                                                   ItemStack stackReference,
                                                   boolean allowHotbar,
                                                   CallbackInfo ci,
                                                   int slotWithItem) {
        // если Tweakeroo не нашёл замену в инвентаре
        if (slotWithItem == -1) {
            // ищем шалкер с нужным предметом
            int shulker = Util.getShulkerWithStack(player.getInventory(), stackReference);
            if (shulker != -1) {
                int slot = Util.getSlotWithStack(
                        ItemStackInventory.getInventoryFromShulker(player.getInventory().getStack(shulker)),
                        stackReference
                );
                if (slot != -1) {
                    ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
                }
            }
        }
    }
}
