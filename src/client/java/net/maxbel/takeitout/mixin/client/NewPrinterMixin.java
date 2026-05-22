package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(ClientPlayerTickManager.class)
public class NewPrinterMixin {

    @Inject(method = "tick", at = @At("HEAD"), remap = false, cancellable = true)
    private static void headHook(CallbackInfo ci) {
        if (!awaitingStack.isEmpty()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private static void tailHook(CallbackInfo ci) {
        if (!TakeitoutClient.AUTOTAKEOUT || !awaitingStack.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) {
            return;
        }

        PrinterBox box = ClientPlayerTickManager.PRINT.getBoxRef().get();
        if (box == null) {
            return;
        }

        for (BlockPos pos : box) {
            SchematicBlockContext ctx = new SchematicBlockContext(mc, mc.level, worldSchematic, pos);

            if (ctx.requiredState == null || ctx.requiredState.isAir()) {
                continue;
            }
            if (ctx.requiredState.equals(ctx.currentState)) {
                continue;
            }
            if (ctx.currentState != null && !ctx.currentState.isAir()) {
                continue;
            }

            ItemStack itemStack = new ItemStack(ctx.getRequiredBlock().asItem());
            int slot = mc.player.getInventory().findSlotMatchingItem(itemStack);
            if (slot != -1) {
                return;
            }

            int shulker = getShulkerWithStack(mc.player.getInventory(), itemStack);
            if (shulker != -1) {
                Container shInv = getInventoryFromShulker(mc.player.getInventory().getItem(shulker));
                slot = getSlotWithStack(shInv, itemStack);
                if (slot != -1) {
                    awaitingStack = itemStack.copyWithCount(1);
                    ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker, TakeitoutClient.SHULKER_SINGLE_ITEM_MODE));
                    break;
                }
            }

            if (WorldContainerSources.requestStack(mc, itemStack, TakeitoutClient.TAKE_SINGLE_ITEM_MODE)) {
                break;
            }
        }
    }
}
