package net.maxbel.takeitout.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;


@Mixin(ClientPlayNetworkHandler.class)
public abstract class UpdatedSlot {

    @Inject(method = "onScreenHandlerSlotUpdate(Lnet/minecraft/network/packet/s2c/play/ScreenHandlerSlotUpdateS2CPacket;)V", at = @At("TAIL"), remap = true)
    public void methodHook(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {

        if(awaitingStack.getItem() == packet.getStack().copyWithCount(1).getItem() && packet.getSyncId() == 0 && PlayerScreenHandler.isInHotbar(packet.getSlot())) {
            awaitingStack = ItemStack.EMPTY;
            //System.out.println("---"+packet.getStack()+"---"+packet.getSlot()+"---"+packet.getSyncId());
        }

    }

}
