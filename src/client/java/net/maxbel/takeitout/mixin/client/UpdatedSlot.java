package net.maxbel.takeitout.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class UpdatedSlot {

    @Inject(
            method = "onScreenHandlerSlotUpdate(Lnet/minecraft/network/packet/s2c/play/ScreenHandlerSlotUpdateS2CPacket;)V",
            at = @At("TAIL")
    )
    private void takeItOut$onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        ItemStack pktStack = getPacketStack(packet); // совместимо с 1.20.1/1.20.6+
        if (pktStack == null) return;

        // hotbar у игрока = syncId 0, проверяем слот в хотбаре
        if (awaitingStack != ItemStack.EMPTY
                && awaitingStack.getItem() == pktStack.copyWithCount(1).getItem()
                && packet.getSyncId() == 0
                && PlayerScreenHandler.isInHotbar(packet.getSlot())) {

            awaitingStack = ItemStack.EMPTY;
            // System.out.println("TakeItOut: received expected stack in hotbar, cleared awaitingStack");
        }
    }

    /**
     * В разных версиях мэппингов метод может называться getStack() или getItemStack().
     * Берём через reflection, чтобы поддержать обе.
     */
    private static ItemStack getPacketStack(ScreenHandlerSlotUpdateS2CPacket packet) {
        try {
            Method m = packet.getClass().getMethod("getStack");
            Object o = m.invoke(packet);
            return (o instanceof ItemStack) ? (ItemStack) o : ItemStack.EMPTY;
        } catch (NoSuchMethodException e1) {
            try {
                Method m = packet.getClass().getMethod("getItemStack");
                Object o = m.invoke(packet);
                return (o instanceof ItemStack) ? (ItemStack) o : ItemStack.EMPTY;
            } catch (Exception e2) {
                return ItemStack.EMPTY;
            }
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
}
