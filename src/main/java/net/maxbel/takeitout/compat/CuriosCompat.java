package net.maxbel.takeitout.compat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

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

    public static List<ItemStack> getEquippedStacks(PlayerEntity player) {
        Optional<ICuriosItemHandler> curios = CuriosApi.getCuriosInventory(player).resolve();
        if (curios.isEmpty()) return List.of();

        IItemHandlerModifiable handler = curios.get().getEquippedCurios();
        List<ItemStack> stacks = new ArrayList<>(handler.getSlots());
        for (int i = 0; i < handler.getSlots(); i++) {
            stacks.add(handler.getStackInSlot(i));
        }
        return stacks;
    }
}
