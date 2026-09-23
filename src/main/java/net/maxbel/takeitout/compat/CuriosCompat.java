package net.maxbel.takeitout.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Optional integration with Curios API. All references to its classes live in this file so they
 * are only class-loaded when this compat layer is actually invoked, which callers must gate
 * behind {@link #isLoaded()}.
 */
public final class CuriosCompat {
    private static final String MOD_ID = "curios";

    private CuriosCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static List<ItemStack> getEquippedStacks(Player player) {
        Optional<ICuriosItemHandler> curios = CuriosApi.getCuriosInventory(player);
        if (curios.isEmpty()) return List.of();

        // MC 26.3 / NeoForge 26.3 replaced the IItemHandler API with the transfer ResourceHandler
        // one, so getEquippedCurios() no longer hands out ItemStacks. Walk the per-slot dynamic
        // handlers instead: getStackInSlot returns the live stack, which callers need in order to
        // extract items from the backpack it holds.
        List<ItemStack> stacks = new ArrayList<>();
        for (ICurioStacksHandler stacksHandler : curios.get().getCurios().values()) {
            IDynamicStackHandler handler = stacksHandler.getStacks();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
        }
        return stacks;
    }
}
