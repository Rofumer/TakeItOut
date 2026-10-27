package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Refills a hand whose last firework rocket was just used.
 *
 * <p>Vanilla only consumes the rocket on the server, so at the moment the client finishes {@code useItem} the
 * stack is still in the hand. Tweakeroo's hand restock checks right then and never fires; the hand is emptied
 * later by a slot update packet. Here the use is remembered and the hand is watched for a short while instead.
 */
public final class FireworkRefill {
    private static final int USE_WINDOW_TICKS = 40;
    private static final int OFFHAND_MOVE_TIMEOUT_TICKS = 100;
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private static final ItemStack[] usedStack = {ItemStack.EMPTY, ItemStack.EMPTY};
    private static final int[] ticksSinceUse = new int[2];
    private static int usedSelectedSlot = -1;

    // The server always delivers into the main hand; an off-hand refill is moved over once it arrives.
    private static ItemStack pendingOffhand = ItemStack.EMPTY;
    private static ItemStack displacedMainHand = ItemStack.EMPTY;
    private static int pendingSelectedSlot = -1;
    private static int pendingTicks;

    private FireworkRefill() {
    }

    public static void onUse(Player player, InteractionHand hand) {
        if (player == null || !player.getItemInHand(hand).is(Items.FIREWORK_ROCKET)) {
            return;
        }

        usedStack[hand.ordinal()] = player.getItemInHand(hand).copyWithCount(1);
        ticksSinceUse[hand.ordinal()] = 0;
        if (hand == InteractionHand.MAIN_HAND) {
            usedSelectedSlot = player.getInventory().getSelectedSlot();
        }
    }

    public static void clear() {
        usedStack[0] = ItemStack.EMPTY;
        usedStack[1] = ItemStack.EMPTY;
        clearPendingOffhand();
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            clear();
            return;
        }

        tickPendingOffhand(mc, player);

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack used = usedStack[hand.ordinal()];
            if (used.isEmpty()) {
                continue;
            }

            if (++ticksSinceUse[hand.ordinal()] > USE_WINDOW_TICKS) {
                usedStack[hand.ordinal()] = ItemStack.EMPTY;
                continue;
            }

            ItemStack current = player.getItemInHand(hand);
            if (current.is(Items.FIREWORK_ROCKET)) {
                continue;
            }

            usedStack[hand.ordinal()] = ItemStack.EMPTY;
            if (!current.isEmpty()
                    || (hand == InteractionHand.MAIN_HAND && player.getInventory().getSelectedSlot() != usedSelectedSlot)) {
                continue;
            }

            if (TakeitoutClient.AUTOTAKEOUT && TakeItOutConfigs.REFILL_FIREWORKS.getBooleanValue()) {
                refill(mc, player, hand, used);
            }
        }
    }

    private static void refill(Minecraft mc, LocalPlayer player, InteractionHand hand, ItemStack reference) {
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int button = hand == InteractionHand.MAIN_HAND ? selected : OFFHAND_SWAP_BUTTON;

        int inventorySlot = findFirework(inventory, reference, selected);
        if (inventorySlot != -1) {
            swap(mc, player, inventorySlot, button);
            return;
        }

        if (!TakeitoutClient.awaitingStack.isEmpty()) {
            return;
        }

        ItemStack mainHandBefore = player.getMainHandItem().copy();
        boolean requested = false;

        int shulker = Util.getShulkerWithStack(inventory, reference);
        if (shulker != -1) {
            Container shulkerInventory = ItemStackInventory.getInventoryFromShulker(inventory.getItem(shulker));
            int slot = Util.getSlotWithStack(shulkerInventory, reference);
            if (slot != -1) {
                TakeitoutClient.awaitingStack = reference.copyWithCount(1);
                ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker, false));
                requested = true;
            }
        }

        if (!requested) {
            requested = WorldContainerSources.requestStack(mc, reference, false);
        }

        if (requested && hand == InteractionHand.OFF_HAND) {
            pendingOffhand = reference.copyWithCount(1);
            displacedMainHand = mainHandBefore;
            pendingSelectedSlot = selected;
            pendingTicks = 0;
        }
    }

    private static void tickPendingOffhand(Minecraft mc, LocalPlayer player) {
        if (pendingOffhand.isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();
        if (++pendingTicks > OFFHAND_MOVE_TIMEOUT_TICKS
                || inventory.getSelectedSlot() != pendingSelectedSlot
                || !player.getOffhandItem().isEmpty()) {
            clearPendingOffhand();
            return;
        }

        if (!player.getMainHandItem().is(Items.FIREWORK_ROCKET)
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return;
        }

        int selected = inventory.getSelectedSlot();
        swap(mc, player, selected, OFFHAND_SWAP_BUTTON);

        // The item held before the refill was pushed into a free slot by the server; put it back in the hand.
        if (!displacedMainHand.isEmpty()) {
            for (int i = 0; i < Util.getSize(inventory); i++) {
                if (i != selected && ItemStack.isSameItemSameComponents(inventory.getItem(i), displacedMainHand)) {
                    swap(mc, player, i, selected);
                    break;
                }
            }
        }

        clearPendingOffhand();
    }

    private static int findFirework(Inventory inventory, ItemStack reference, int selected) {
        int anyFirework = -1;
        for (int i = 0; i < Util.getSize(inventory); i++) {
            ItemStack stack = inventory.getItem(i);
            if (i == selected || !stack.is(Items.FIREWORK_ROCKET)) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(stack, reference)) {
                return i;
            }
            if (anyFirework == -1) {
                anyFirework = i;
            }
        }
        return anyFirework;
    }

    /** Swaps an inventory slot with a hotbar slot (button 0-8) or with the off hand (button 40). */
    private static void swap(Minecraft mc, LocalPlayer player, int inventorySlot, int button) {
        int menuSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, button, ContainerInput.SWAP, player);
    }

    private static void clearPendingOffhand() {
        pendingOffhand = ItemStack.EMPTY;
        displacedMainHand = ItemStack.EMPTY;
        pendingSelectedSlot = -1;
        pendingTicks = 0;
    }
}
