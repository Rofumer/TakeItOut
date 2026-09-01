package net.maxbel.takeitout.client;

import net.maxbel.takeitout.Takeitout;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which linked container every item currently in the inventory was taken from, so that a full
 * inventory can be freed by sending the oldest material back where it came from instead of dumping it
 * somewhere arbitrary.
 *
 * <p>Session-only state: nothing is written to disk, and everything is dropped on world/context change.
 */
public final class WorldContainerReturnTracker {
    /**
     * One entry per (container, item key) pair. {@code seq} is a monotonic counter marking when the item
     * was first taken from that container. It is deliberately NOT refreshed when the same item is taken
     * from the same container again: the feature wants the material the player started with longest ago,
     * so repeatedly topping up a stack must not make it look freshly used.
     */
    private record Entry(Takeitout.WorldContainerSource source, ItemStack keyStack, String itemKey, long seq) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static long seqCounter = 0L;

    private WorldContainerReturnTracker() {
    }

    public static void clear() {
        ENTRIES.clear();
        seqCounter = 0L;
    }

    /** Records a confirmed take. Called only for a server response with {@code success == true}. */
    public static void recordTake(Takeitout.WorldContainerSource source, ItemStack stack) {
        if (source == null || stack == null || stack.isEmpty()) return;
        String itemKey = itemKey(stack);
        String key = mapKey(source, itemKey);
        // Keep the original timestamp of an existing entry (see Entry docs).
        if (ENTRIES.containsKey(key)) return;
        ENTRIES.put(key, new Entry(source, stack.copyWithCount(1), itemKey, seqCounter++));
    }

    /**
     * Drops the entry for a completed return. Matching is by container + item key only; the timestamp is
     * never part of the comparison.
     */
    public static void forgetReturned(Takeitout.WorldContainerSource source, ItemStack stack) {
        if (source == null || stack == null || stack.isEmpty()) return;
        ENTRIES.remove(mapKey(source, itemKey(stack)));
    }

    /** Removes entries whose items are no longer anywhere in the player's 36 inventory slots. */
    public static void prune(LocalPlayer player) {
        if (player == null) {
            clear();
            return;
        }
        ENTRIES.values().removeIf(entry -> countInInventory(player, entry.keyStack(), -1) <= 0);
    }

    /**
     * Picks the item that should go back into its container to free a slot.
     *
     * <p>Preference order: oldest take first, then the smallest amount held (so the least useful material
     * is given up). Items that must not be touched are skipped: the stack in the main hand, the item that
     * is being requested right now, and anything the server-side {@code canReplaceInventoryItem} would
     * reject anyway.
     *
     * @return the chosen victim, or {@code null} when there is nothing safe to return
     */
    public static Victim pickVictim(LocalPlayer player, ItemStack requested) {
        if (player == null || requested == null || requested.isEmpty()) return null;

        prune(player);
        if (ENTRIES.isEmpty()) return null;

        String requestedKey = itemKey(requested);
        int selectedSlot = player.getInventory().getSelectedSlot();

        Entry best = null;
        int bestCount = 0;
        for (Entry entry : new ArrayList<>(ENTRIES.values())) {
            if (entry.itemKey().equals(requestedKey)) continue;

            int count = countInInventory(player, entry.keyStack(), selectedSlot);
            if (count <= 0) continue;

            if (best == null || entry.seq() < best.seq() || (entry.seq() == best.seq() && count < bestCount)) {
                best = entry;
                bestCount = count;
            }
        }

        return best == null ? null : new Victim(best.source(), best.keyStack(), bestCount);
    }

    public record Victim(Takeitout.WorldContainerSource source, ItemStack keyStack, int count) {
    }

    /** True when every one of the 36 main inventory slots holds something. */
    public static boolean isInventoryFull(LocalPlayer player) {
        if (player == null) return false;
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int i = 0; i < size; i++) {
            if (player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /** True when the requested item can still be merged into an existing stack, so no slot has to be freed. */
    public static boolean hasRoomForMore(LocalPlayer player, ItemStack requested) {
        if (player == null || requested == null || requested.isEmpty()) return false;
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && ItemStack.isSameItemSameComponents(stack, requested)
                    && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts returnable items matching {@code keyStack} in the 36 main slots.
     *
     * @param excludedSlot slot to skip (the held one), or {@code -1} to count everything
     */
    private static int countInInventory(LocalPlayer player, ItemStack keyStack, int excludedSlot) {
        boolean componentSensitive = isComponentSensitive(keyStack);
        int size = Math.min(36, player.getInventory().getContainerSize());
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (i == excludedSlot) continue;
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (excludedSlot != -1 && !canReplaceInventoryItem(stack)) continue;

            boolean matches = componentSensitive
                    ? ItemStack.isSameItemSameComponents(stack, keyStack)
                    : stack.is(keyStack.getItem()) && stack.getComponentsPatch().isEmpty();
            if (matches) count += stack.getCount();
        }
        return count;
    }

    private static String mapKey(Takeitout.WorldContainerSource source, String itemKey) {
        return WorldContainerSources.sourceKey(source) + "#" + itemKey;
    }

    /**
     * Item identity used for tracking. Plain items collapse to their item id, but shulker boxes and any
     * stack carrying components keep those components in the key - otherwise a return would move the
     * wrong item.
     */
    private static String itemKey(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return isComponentSensitive(stack) ? id + "|" + stack.getComponentsPatch() : id;
    }

    private static boolean isComponentSensitive(ItemStack stack) {
        return isShulkerItem(stack) || !stack.getComponentsPatch().isEmpty();
    }

    private static boolean isShulkerItem(ItemStack item) {
        return !item.isEmpty()
                && item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Client-side mirror of {@code Takeitout.canReplaceInventoryItem}; the server check stays authoritative. */
    private static boolean canReplaceInventoryItem(ItemStack item) {
        if (item == null || item.isEmpty()) return false;

        if (item.getItem() instanceof HoeItem
                || item.getItem() instanceof AxeItem
                || item.getItem() instanceof ShovelItem) {
            return false;
        }

        if (!(item.getItem() instanceof BlockItem blockItem)) return false;

        return !(blockItem.getBlock() instanceof ShulkerBoxBlock)
                && !(blockItem.getBlock() instanceof EnderChestBlock);
    }

    /** Debug helper: current number of tracked (container, item) pairs. */
    public static int size() {
        return ENTRIES.size();
    }

    /** Debug helper: snapshot of tracked entries as "container#item" keys. */
    public static List<String> keysSnapshot() {
        return new ArrayList<>(ENTRIES.keySet());
    }
}
