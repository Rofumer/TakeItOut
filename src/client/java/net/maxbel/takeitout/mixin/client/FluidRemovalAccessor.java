package net.maxbel.takeitout.mixin.client;

import java.util.List;
import me.aleksilassila.litematica.printer.handler.handlers.FluidRemoval;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the item list the printer resolved from {@code Configs.Fluid.FLUID_REPLACE_BLOCK_LIST}
 * so we know which items are allowed to soak up fluids before requesting them from containers.
 */
@Mixin(FluidRemoval.class)
public interface FluidRemovalAccessor {

    @Accessor(value = "fillItems", remap = false)
    List<Item> takeitout$getFillItems();
}
