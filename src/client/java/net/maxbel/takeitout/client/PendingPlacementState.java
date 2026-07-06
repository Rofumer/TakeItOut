package net.maxbel.takeitout.client;

import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

public class PendingPlacementState {
    public static BlockPos pos = null;
    public static Item expectedItem = null;
    public static int ticks = 0;
    public static boolean needsUseRetry = false;
    public static boolean useRetried = false;
    public static final int VERIFY_TIMEOUT_TICKS = 8;

    public static void reset() {
        pos = null;
        expectedItem = null;
        ticks = 0;
        needsUseRetry = false;
        useRetried = false;
    }
}
