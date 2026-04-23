package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.tweakeroo.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.Util;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(InventoryUtils.class)
public class TweakerooMixin {

    @Inject(
            method = "restockNewStackToHand",
            at = @At("TAIL"),
            cancellable = false,
            locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false
    )
    private static void restockNewStackToHand_tail(Player player,
                                                   InteractionHand hand,
                                                   ItemStack stackReference,
                                                   boolean allowHotbar,
                                                   CallbackInfo ci,
                                                   int slotWithItem) {
        if (slotWithItem == -1) {
            int shulker = Util.getShulkerWithStack(player.getInventory(), stackReference);
            if (shulker != -1) {
                int slot = Util.getSlotWithStack(
                        ItemStackInventory.getInventoryFromShulker(player.getInventory().getItem(shulker)),
                        stackReference
                );
                if (slot != -1) {
                    ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker, TakeitoutClient.TAKE_SINGLE_ITEM_MODE));
                    return;
                }
            }

            WorldContainerSources.requestStack(Minecraft.getInstance(), stackReference, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
        }
    }
}
