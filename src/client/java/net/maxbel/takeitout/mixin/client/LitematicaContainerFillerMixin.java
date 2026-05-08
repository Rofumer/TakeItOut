package net.maxbel.takeitout.mixin.client;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Queue;
import java.util.Collection;
import java.util.Set;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.TAKE_SINGLE_ITEM_MODE;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(value = AutoFillerStateMachine.class, remap = false)
public abstract class LitematicaContainerFillerMixin {

    @Shadow private int actionWaitTicks;
    @Shadow private int watchdogTimer;
    @Shadow private int stashShulkerSlot;
    @Shadow private int stashItemSlot;
    @Shadow private AutoFillerStateMachine.FillTask currentTask;
    @Final
    @Shadow private Queue<Integer> pendingShulkers;

    @Shadow
    protected abstract void changePhase(AutoFillerStateMachine.Phase newPhase);

    @Shadow
    protected abstract List<Integer> getEmptySlots(MinecraftClient client);

    @Shadow
    protected abstract int[] findStashAction(MinecraftClient client, Collection<ItemStack> protectedItems);

    @Shadow
    protected abstract List<ItemStack> computeNeededToFetch(MinecraftClient client);

    @Shadow
    protected abstract Set<Integer> findShulkersContaining(MinecraftClient client, List<ItemStack> needed);

    @Shadow
    protected abstract boolean hasAnyMaterialsToFill(MinecraftClient client);

    @Shadow
    protected abstract void abortTask(MinecraftClient client, String errorMsgKey, boolean isInventoryFull, boolean isLeaking);

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pauseWhileTakeItOutFetchInFlight(MinecraftClient client, CallbackInfo ci) {
        if (this.currentTask != null && !awaitingStack.isEmpty()) {
            this.watchdogTimer = 0;
            ci.cancel();
        }
    }

    @Inject(method = "checkAndStartGatheringOrFilling", at = @At("HEAD"), cancellable = true)
    private void requestMissingStackViaTakeItOut(MinecraftClient client, CallbackInfo ci) {
        boolean isCreativeFill = client.player != null
                && client.player.isCreative()
                && Configs.ENABLE_CREATIVE_FILL.getBooleanValue();

        int emptySlots = this.getEmptySlots(client).size();
        if (emptySlots == 0 && Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = this.findStashAction(client, this.currentTask.requiredItems.values());
            if (stashAction != null) {
                this.stashShulkerSlot = stashAction[0];
                this.stashItemSlot = stashAction[1];
                this.changePhase(AutoFillerStateMachine.Phase.STASHING);
                ci.cancel();
                return;
            }
        }

        if (isCreativeFill) {
            this.currentTask.initLedger();
            this.changePhase(AutoFillerStateMachine.Phase.FILLING);
            ci.cancel();
            return;
        }

        this.pendingShulkers.clear();
        List<ItemStack> needed = this.computeNeededToFetch(client);
        if (!needed.isEmpty()) {
            Set<Integer> shulkers = this.findShulkersContaining(client, needed);
            if (!shulkers.isEmpty()) {
                this.pendingShulkers.addAll(shulkers);
                this.changePhase(AutoFillerStateMachine.Phase.GATHERING);
                ci.cancel();
                return;
            }

            if (requestMissingViaTakeItOut(client, needed)) {
                this.actionWaitTicks = 1;
                this.currentTask.initLedger();
                this.changePhase(AutoFillerStateMachine.Phase.FILLING);
                ci.cancel();
                return;
            }

            if (this.hasAnyMaterialsToFill(client)) {
                this.currentTask.initLedger();
                this.changePhase(AutoFillerStateMachine.Phase.FILLING);
                ci.cancel();
                return;
            }

            this.abortTask(client, "litematica_container_filler.message.materials_depleted", false, false);
            ci.cancel();
            return;
        }

        this.currentTask.initLedger();
        this.changePhase(AutoFillerStateMachine.Phase.FILLING);
        ci.cancel();
    }

    @Unique
    private static boolean requestMissingViaTakeItOut(MinecraftClient client, List<ItemStack> needed) {
        for (ItemStack stack : needed) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            ItemStack requested = stack.copyWithCount(1);
            if (requestFromInventoryShulker(client, requested) || WorldContainerSources.requestStack(client, requested, TAKE_SINGLE_ITEM_MODE)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean requestFromInventoryShulker(MinecraftClient client, ItemStack required) {
        int shulkerSlot = getShulkerWithStack(client.player.getInventory(), required);
        if (shulkerSlot == -1) {
            return false;
        }

        Inventory shulkerInventory = (Inventory) getInventoryFromShulker(client.player.getInventory().getStack(shulkerSlot));
        int innerSlot = getSlotWithStack(shulkerInventory, required);
        if (innerSlot == -1) {
            return false;
        }

        awaitingStack = required.copyWithCount(1);
        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(innerSlot, shulkerSlot, TAKE_SINGLE_ITEM_MODE));
        return true;
    }
}
