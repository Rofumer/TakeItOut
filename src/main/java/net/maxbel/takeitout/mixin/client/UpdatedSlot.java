package net.maxbel.takeitout.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
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

        // containerId == 0 -> player inventory menu updates.
        // For printer flow we only need to know that the requested item arrived,
        // it may not always be reflected as a hotbar slot update first.
        if (packet.getContainerId() == 0
                && !stack.isEmpty()
                && ItemStack.isSameItem(awaitingStack, stack)) {
            awaitingStack = ItemStack.EMPTY;
        }
    }
}
