package net.maxbel.takeitout.compat;

import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;

import java.util.Optional;

/**
 * Optional integration with Sophisticated Backpacks. All references to its classes live in this
 * file (and {@link BackpackContainerView}) so they are only class-loaded when this compat layer
 * is actually invoked, which callers must gate behind {@link #isLoaded()}.
 */
public final class SophisticatedBackpacksCompat {
    private static final String MOD_ID = "sophisticatedbackpacks";

    private SophisticatedBackpacksCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isBackpackBlock(Block block) {
        return block instanceof BackpackBlock;
    }

    public static Inventory asContainer(BlockEntity blockEntity) {
        if (!(blockEntity instanceof BackpackBlockEntity backpackBlockEntity)) return null;
        return new BackpackContainerView(backpackBlockEntity.getBackpackWrapper().getInventoryHandler());
    }

    public static boolean isBackpackItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BackpackItem;
    }

    /** Reads/writes the real backpack contents; safe to call only from the server thread. */
    public static Inventory asContainer(ItemStack backpackStack) {
        Optional<IBackpackWrapper> wrapper =
                backpackStack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).resolve();
        return wrapper.<Inventory>map(w -> new BackpackContainerView(w.getInventoryHandler())).orElse(null);
    }
}
