package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.AUTOTAKEOUT;
import static net.maxbel.takeitout.client.TakeitoutClient.TAKE_SINGLE_ITEM_MODE;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Restriction(require = @Condition(type = Condition.Type.MOD, value = "forgematica_printer"))
@Mixin(Printer.class)
public abstract class PrinterMixin {

    @Shadow(remap = false)
    protected abstract List<BlockPos> getReachablePositions();

    @Shadow(remap = false)
    @Final
    public ClientPlayerEntity player;

    @Inject(method = "me.aleksilassila.litematica.printer.Printer.onGameTick", at = @At("TAIL"), remap = false)
    public void methodHookTail(CallbackInfoReturnable<Boolean> cir) {
        if (TakeitoutClient.AUTOTAKEOUT && awaitingStack.isEmpty()) {

            ItemStack itemStack;
            int slot;

            WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
            List<BlockPos> positions = this.getReachablePositions();
            for (BlockPos position : positions) {
                SchematicBlockState state = new SchematicBlockState(this.player.getWorld(), worldSchematic, position);
                if (state.targetState.equals(state.currentState) || state.targetState.isAir()) {
                    continue;
                }
                if (!state.targetState.equals(state.currentState) && !state.currentState.isReplaceable()) {
                    continue;
                }
                itemStack = new ItemStack(state.targetState.getBlock().asItem());
                slot = player.getInventory().getSlotWithStack(itemStack);
                if(slot != -1) return;
                int shulker = getShulkerWithStack(player.getInventory(), itemStack);

                if (shulker != -1) {
                    slot = getSlotWithStack((Inventory) (getInventoryFromShulker((ItemStack) player.getInventory().getStack(shulker))), itemStack);
                    if (slot != -1) {
                        awaitingStack = itemStack;
                        TakeitoutClient.sendToServer(new Takeitout.GetShulkerStackPayload(slot, shulker, TAKE_SINGLE_ITEM_MODE));
                        break;
                    }
                }
                if (WorldContainerSources.requestStack(MinecraftClient.getInstance(), itemStack, TAKE_SINGLE_ITEM_MODE)) {
                    break;
                }
            }

        }
    }

    @Inject(method = "me.aleksilassila.litematica.printer.Printer.onGameTick", at = @At("HEAD"), remap = false, cancellable = true)
    public void methodHookHead(CallbackInfoReturnable<Boolean> cir) {

        if (!awaitingStack.isEmpty()) {
            cir.setReturnValue(false);
            cir.cancel();
        }

    }
}
