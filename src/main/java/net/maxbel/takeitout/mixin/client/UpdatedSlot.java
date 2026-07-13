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

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), remap = true)
    public void methodHook(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {

        if(!awaitingStack.isEmpty() && awaitingStack.getItem() == packet.getItem().copyWithCount(1).getItem() && packet.getContainerId() == 0 && InventoryMenu.isHotbarSlot(packet.getSlot())) {
            awaitingStack = ItemStack.EMPTY;
        }

    }

}
