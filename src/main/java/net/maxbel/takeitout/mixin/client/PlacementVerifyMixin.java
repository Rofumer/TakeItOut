package net.maxbel.takeitout.mixin.client;

import net.maxbel.takeitout.client.PendingPlacementState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class PlacementVerifyMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/mouse");

    @Inject(method = "tick", at = @At("TAIL"))
    private void takeitout$verifyPlacement(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;

        if (PendingPlacementState.pos == null || PendingPlacementState.expectedItem == null || client.world == null) {
            return;
        }

        PendingPlacementState.ticks++;

        if (PendingPlacementState.needsUseRetry && !PendingPlacementState.useRetried && client.player != null && client.currentScreen == null) {
            ItemStack handNow = client.player.getStackInHand(Hand.MAIN_HAND);
            if (handNow.isOf(PendingPlacementState.expectedItem)) {
                LOGGER.debug(
                        "PKM retry use after delayed pick-block: pos={}, expected={}, handNow={}, ticksWaited={}",
                        PendingPlacementState.pos,
                        PendingPlacementState.expectedItem,
                        handNow,
                        PendingPlacementState.ticks
                );
                ((MinecraftClientAccessor) (Object) client).takeitout$invokeDoItemUse();
                PendingPlacementState.useRetried = true;
                PendingPlacementState.needsUseRetry = false;
            }
        }

        ItemStack nowAtPos = new ItemStack(client.world.getBlockState(PendingPlacementState.pos).getBlock().asItem());
        boolean placed = nowAtPos.isOf(PendingPlacementState.expectedItem);
        if (placed) {
            LOGGER.debug(
                    "PKM placement SUCCESS: pos={}, expected={}, actual={}, ticksWaited={}",
                    PendingPlacementState.pos,
                    PendingPlacementState.expectedItem,
                    nowAtPos,
                    PendingPlacementState.ticks
            );
            PendingPlacementState.reset();
            return;
        }

        if (PendingPlacementState.ticks >= PendingPlacementState.VERIFY_TIMEOUT_TICKS) {
            LOGGER.warn(
                    "PKM placement FAIL/TIMEOUT: pos={}, expected={}, actual={}, ticksWaited={}",
                    PendingPlacementState.pos,
                    PendingPlacementState.expectedItem,
                    nowAtPos,
                    PendingPlacementState.ticks
            );
            PendingPlacementState.reset();
        }
    }
}
