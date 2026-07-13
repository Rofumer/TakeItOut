package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.tweakeroo.util.InventoryUtils;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.ItemStackInventory;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.Util;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import static net.maxbel.takeitout.client.TakeitoutClient.AUTOTAKEOUT;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;

@Mixin(InventoryUtils.class)
public class TweakerooMixin {

    /**
     * restockNewStackToHand(Player player, InteractionHand hand, ItemStack stackReference, boolean allowHotbar) : void
     * Capture the local slotWithItem from Tweakeroo's restock flow.
     */
    @Inject(
            method = "restockNewStackToHand",
            at = @At("TAIL"),
            cancellable = false,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private static void restockNewStackToHand_tail(Player player,
                                                   InteractionHand hand,
                                                   ItemStack stackReference,
                                                   boolean allowHotbar,
                                                   CallbackInfo ci,
                                                   int slotWithItem) {
        if (!AUTOTAKEOUT || player == null || stackReference == null || stackReference.isEmpty()) {
            return;
        }

        // Do not send a second request while the previous delivery is still pending.
        if (!awaitingStack.isEmpty()) {
            return;
        }

        // If Tweakeroo didn't find a replacement in inventory, try shulkers/world containers.
        if (slotWithItem == -1) {
            if (player.getItemInHand(hand).is(stackReference.getItem())) {
                return;
            }

            int shulker = Util.getShulkerWithStack(player.getInventory(), stackReference);
            if (shulker != -1) {
                int slot = Util.getSlotWithStack(
                        ItemStackInventory.getInventoryFromShulker(player.getInventory().getItem(shulker)),
                        stackReference
                );
                if (slot != -1) {
                    awaitingStack = stackReference.copyWithCount(1);
                    net.maxbel.takeitout.client.TakeitoutClient.sendToServer(new Takeitout.GetShulkerStackPayload(slot, shulker, TakeitoutClient.TAKE_SINGLE_ITEM_MODE));
                    return;
                }
            }

            WorldContainerSources.requestStack(Minecraft.getInstance(), stackReference, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
        }
    }
}
