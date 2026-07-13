package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import net.maxbel.takeitout.client.SchematicBlockState;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Restriction(require = @Condition(type = Condition.Type.MOD, value = "litematica"))
@Mixin(MouseHandler.class)
public class MouseMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/mouse");
    @Unique private static BlockPos pendingPlacementPos = null;
    @Unique private static Item pendingPlacementExpectedItem = null;
    @Unique private static int pendingPlacementTicks = 0;
    @Unique private static boolean pendingPlacementNeedsUseRetry = false;
    @Unique private static boolean pendingPlacementUseRetried = false;
    @Unique private static final int PLACEMENT_VERIFY_TIMEOUT_TICKS = 8;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(
            method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void takeitout$onButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (input.button() != 1 || action != 1) {
            return;
        }

        if (!TakeitoutClient.AUTOTAKEOUT) {
            return;
        }

        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }

        if (this.minecraft.screen != null) {
            return;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }

        if (Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            return;
        }

        // 1) Raycast into the schematic world, respecting layer/slice visibility
        BlockHitResult schematicHit = RayTraceUtils.traceToSchematicWorld(this.minecraft.player, 5, true, true);
        if (schematicHit == null || schematicHit.getBlockPos() == null) {
            return;
        }

        // 2) Vanilla raycast against the real world — block "shoot-through" hologram picking
        final double reach = 5.0D;
        final float tickDelta = 1.0F;

        Vec3 start = this.minecraft.player.getEyePosition(tickDelta);
        Vec3 look = this.minecraft.player.getViewVector(tickDelta);
        Vec3 end = start.add(look.x * reach, look.y * reach, look.z * reach);

        HitResult worldHit = this.minecraft.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this.minecraft.player
        ));

        if (worldHit != null && worldHit.getType() == HitResult.Type.BLOCK) {
            BlockPos worldPos = ((BlockHitResult) worldHit).getBlockPos();
            BlockPos schemPos = schematicHit.getBlockPos();

            if (worldPos.equals(schemPos)) {
                return;
            }

            double worldDist = start.distanceTo(worldHit.getLocation());
            double schematicDist = start.distanceTo(schematicHit.getLocation());
            if (worldDist + 1.0e-6 < schematicDist) {
                return;
            }
        }

        // 3) Make sure the schematic actually wants something here (not air)
        SchematicBlockState st = new SchematicBlockState(this.minecraft.level, schematicWorld, schematicHit.getBlockPos());
        if (st.targetState == null || st.targetState.isAir()) {
            return;
        }

        // Only trigger on a hologram mismatch — if the real block already matches the target, don't pick.
        if (st.currentState != null && st.targetState.equals(st.currentState)) {
            return;
        }

        int selectedSlot = this.minecraft.player.getInventory().getSelectedSlot();
        ItemStack inHand = this.minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack wanted = new ItemStack(st.targetState.getBlock().asItem());
        ItemStack current = new ItemStack(st.currentState.getBlock().asItem());

        LOGGER.debug(
                "PKM start: pos={}, hologramTarget={}, worldCurrent={}, handItem={}, handSlot={}, matchInHand={}",
                schematicHit.getBlockPos(),
                wanted,
                current,
                inHand,
                selectedSlot,
                inHand.is(wanted.getItem())
        );

        // 4) Pick the required block from the schematic; don't cancel the event — vanilla handles the click.
        WorldUtils.doSchematicWorldPickBlock(true, this.minecraft);

        ItemStack afterPickInHand = this.minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        LOGGER.debug(
                "PKM after pick-block: pos={}, required={}, handNow={}, handSlot={}",
                schematicHit.getBlockPos(),
                wanted,
                afterPickInHand,
                selectedSlot
        );

        pendingPlacementPos = schematicHit.getBlockPos().immutable();
        pendingPlacementExpectedItem = wanted.getItem();
        pendingPlacementTicks = 0;
        pendingPlacementNeedsUseRetry = !afterPickInHand.is(wanted.getItem());
        pendingPlacementUseRetried = false;
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void takeitout$verifyPlacement(CallbackInfo ci) {
        if (pendingPlacementPos == null || pendingPlacementExpectedItem == null || this.minecraft == null || this.minecraft.level == null) {
            return;
        }

        pendingPlacementTicks++;

        if (pendingPlacementNeedsUseRetry && !pendingPlacementUseRetried && this.minecraft.player != null && this.minecraft.screen == null) {
            ItemStack handNow = this.minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
            if (handNow.is(pendingPlacementExpectedItem)) {
                LOGGER.debug(
                        "PKM retry use after delayed pick-block: pos={}, expected={}, handNow={}, ticksWaited={}",
                        pendingPlacementPos,
                        pendingPlacementExpectedItem,
                        handNow,
                        pendingPlacementTicks
                );
                ((MinecraftClientAccessor) (Object) this.minecraft).takeitout$invokeDoItemUse();
                pendingPlacementUseRetried = true;
                pendingPlacementNeedsUseRetry = false;
            }
        }

        ItemStack nowAtPos = new ItemStack(this.minecraft.level.getBlockState(pendingPlacementPos).getBlock().asItem());
        boolean placed = nowAtPos.is(pendingPlacementExpectedItem);
        if (placed) {
            LOGGER.debug(
                    "PKM placement SUCCESS: pos={}, expected={}, actual={}, ticksWaited={}",
                    pendingPlacementPos,
                    pendingPlacementExpectedItem,
                    nowAtPos,
                    pendingPlacementTicks
            );
            pendingPlacementPos = null;
            pendingPlacementExpectedItem = null;
            pendingPlacementTicks = 0;
            pendingPlacementNeedsUseRetry = false;
            pendingPlacementUseRetried = false;
            return;
        }

        if (pendingPlacementTicks >= PLACEMENT_VERIFY_TIMEOUT_TICKS) {
            LOGGER.warn(
                    "PKM placement FAIL/TIMEOUT: pos={}, expected={}, actual={}, ticksWaited={}",
                    pendingPlacementPos,
                    pendingPlacementExpectedItem,
                    nowAtPos,
                    pendingPlacementTicks
            );
            pendingPlacementPos = null;
            pendingPlacementExpectedItem = null;
            pendingPlacementTicks = 0;
            pendingPlacementNeedsUseRetry = false;
            pendingPlacementUseRetried = false;
        }
    }
}
