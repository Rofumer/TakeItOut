package net.maxbel.takeitout.client;

import java.util.List;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.mixin.client.FluidRemovalAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

/**
 * Keeps the printer's fluid removal module supplied. The module soaks up the fluids listed in
 * {@code Configs.Fluid.FLUID_LIST} with the blocks listed in {@code Configs.Fluid.FLUID_REPLACE_BLOCK_LIST}
 * (sand by default). When those run out it only reports them through {@link MissingMaterialTracker},
 * so we use that report as the signal to pull a refill out of a shulker or a linked world container.
 */
public final class PrinterFluidSupport {

    private PrinterFluidSupport() {
    }

    /**
     * @return true when a request was sent and the printer should stay paused this tick
     */
    public static boolean tryRefill(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        List<Item> fillItems = getFillItems();
        if (fillItems == null || fillItems.isEmpty()) {
            return false;
        }

        // The printer can still reach the items itself (main inventory, hotbar or offhand).
        if (InventoryUtils.playerHasAccessToItems(mc.player, fillItems.toArray(new Item[0]))) {
            return false;
        }

        if (!isReportedMissing(fillItems)) {
            return false;
        }

        for (Item item : fillItems) {
            if (item == null) {
                continue;
            }
            if (request(mc, new ItemStack(item))) {
                return true;
            }
        }

        return false;
    }

    private static List<Item> getFillItems() {
        try {
            return ((FluidRemovalAccessor) (Object) ModuleManager.FLUID_REMOVAL).takeitout$getFillItems();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The fluid module reports the first configured fill block whenever none of them is reachable. */
    private static boolean isReportedMissing(List<Item> fillItems) {
        MissingMaterialTracker tracker = MissingMaterialTracker.getInstance();
        if (!tracker.hasMissing()) {
            return false;
        }
        for (MissingMaterialTracker.Entry entry : tracker.getMissing()) {
            if (entry != null && fillItems.contains(entry.item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean request(Minecraft mc, ItemStack stack) {
        int shulker = getShulkerWithStack(mc.player.getInventory(), stack);
        if (shulker != -1) {
            Container shulkerInventory = getInventoryFromShulker(mc.player.getInventory().getItem(shulker));
            int slot = getSlotWithStack(shulkerInventory, stack);
            if (slot != -1) {
                TakeitoutClient.awaitingStack = stack.copyWithCount(1);
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(
                        slot, shulker, TakeitoutClient.SHULKER_SINGLE_ITEM_MODE));
                return true;
            }
        }

        return WorldContainerSources.requestStack(mc, stack, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
    }
}
