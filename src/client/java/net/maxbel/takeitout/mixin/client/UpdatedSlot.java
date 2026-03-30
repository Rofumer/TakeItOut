package net.maxbel.takeitout.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;

@Mixin(ClientPacketListener.class)
public abstract class UpdatedSlot {

    @Inject(
            method = "handleContainerSetSlot",
            at = @At("TAIL")
    )
    private void methodHook(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {

        if (awaitingStack.isEmpty()) {
            return;
        }

        ItemStack stack = packet.getItem();

        // syncId == 0 → инвентарь игрока
        if (packet.getContainerId() == 0 &&
                InventoryMenu.isHotbarSlot(packet.getSlot()) &&
                ItemStack.isSameItem(awaitingStack, stack) &&
                ItemStack.matches(awaitingStack, stack)) {

            awaitingStack = ItemStack.EMPTY;
        }
    }
}