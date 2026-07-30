package net.maxbel.takeitout.compat;

import com.github.quiltservertools.ledger.callbacks.ItemInsertCallback;
import com.github.quiltservertools.ledger.callbacks.ItemRemoveCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Optional integration with Ledger.
 *
 * <p>This class must only be loaded after checking that the {@code ledger} mod
 * is present. Keeping all direct Ledger references here allows TakeItOut to run
 * normally when Ledger is not installed.</p>
 */
public final class LedgerCompat {
    private static final String SOURCE = "takeitout";

    private LedgerCompat() {
    }

    public static void logItemRemove(
            ServerPlayer player,
            ServerLevel world,
            BlockPos pos,
            ItemStack stack
    ) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        ItemRemoveCallback.EVENT.invoker().remove(stack.copy(), pos, world, SOURCE, player);
    }

    public static void logItemInsert(
            ServerPlayer player,
            ServerLevel world,
            BlockPos pos,
            ItemStack stack
    ) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        ItemInsertCallback.EVENT.invoker().insert(stack.copy(), pos, world, SOURCE, player);
    }
}
