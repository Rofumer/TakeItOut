package net.maxbel.takeitout.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class WorldContainerSourceRenderer {
    private static final float OUTLINE_ALPHA = 1.0F;
    private static final double OUTLINE_PADDING = 0.002D;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 128.0D * 128.0D;
    private static final VoxelShape OUTLINE_SHAPE = Shapes.box(
            -OUTLINE_PADDING,
            -OUTLINE_PADDING,
            -OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING
    );

    private WorldContainerSourceRenderer() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(WorldContainerSourceRenderer::onSubmitCustomGeometry);
    }

    private static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        if (!TakeitoutClient.RENDER_CONTAINER_SOURCES) {
            return;
        }

        var cameraState = event.getLevelRenderState().cameraRenderState;
        if (!cameraState.initialized) {
            return;
        }

        Vec3 cameraPos = cameraState.pos;
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        int color = TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR;

        for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
            if (Vec3.atCenterOf(source).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();
            poseStack.translate(
                    source.getX() - cameraPos.x,
                    source.getY() - cameraPos.y,
                    source.getZ() - cameraPos.z
            );
            collector.submitShapeOutline(poseStack, OUTLINE_SHAPE, RenderTypes.lines(), color, OUTLINE_ALPHA, false);
            poseStack.popPose();
        }

        int dumpColor = 0xFFF97316;
        for (BlockPos dump : WorldContainerDumps.getDumpSnapshot()) {
            if (Vec3.atCenterOf(dump).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();
            poseStack.translate(
                    dump.getX() - cameraPos.x,
                    dump.getY() - cameraPos.y,
                    dump.getZ() - cameraPos.z
            );
            collector.submitShapeOutline(poseStack, OUTLINE_SHAPE, RenderTypes.lines(), dumpColor, OUTLINE_ALPHA, false);
            poseStack.popPose();
        }
    }
}
