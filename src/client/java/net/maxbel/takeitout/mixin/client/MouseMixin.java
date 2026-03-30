package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.maxbel.takeitout.client.SchematicBlockState;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(
            method = "onPress(JIII)V",
            at = @At("HEAD"),
            require = 0
    )
    private void takeitout$onPress(long window, int button, int action, int mods, CallbackInfo ci) {

        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }

        if (action != GLFW.GLFW_PRESS) {
            return;
        }

        if (this.minecraft == null) {
            return;
        }

        if (!TakeitoutClient.AUTOTAKEOUT) {
            return;
        }

        if (this.minecraft.player == null || this.minecraft.level == null) {
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

        BlockHitResult schematicHit = RayTraceUtils.traceToSchematicWorld(this.minecraft.player, 5.0D, true, true);
        if (schematicHit == null || schematicHit.getBlockPos() == null) {
            return;
        }

        double reach = 5.0D;
        float tickDelta = 1.0F;

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

            if (worldDist + 1.0e-6D < schematicDist) {
                return;
            }
        }

        SchematicBlockState st = new SchematicBlockState(
                this.minecraft.level,
                schematicWorld,
                schematicHit.getBlockPos()
        );

        if (st.targetState == null || st.targetState.isAir()) {
            return;
        }

        WorldUtils.doSchematicWorldPickBlock(true, this.minecraft);
    }
}