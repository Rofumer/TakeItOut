package net.maxbel.takeitout.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class WorldContainerSourceRenderer {
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

    public static void register() {
        NeoForge.EVENT_BUS.addListener(WorldContainerSourceRenderer::onRenderLevelStage);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        render(event.getPoseStack());
    }

    private static void render(PoseStack poseStack) {
        Minecraft client = Minecraft.getInstance();
        if (!TakeitoutClient.RENDER_CONTAINER_SOURCES || client.level == null) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = consumers.getBuffer(RenderType.lines());

        for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
            if (source.getCenter().distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            drawOutline(
                    poseStack,
                    vertexConsumer,
                    source.getX() - cameraPos.x,
                    source.getY() - cameraPos.y,
                    source.getZ() - cameraPos.z,
                    TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR
            );
        }

        int dumpColor = 0xFFF97316;
        for (BlockPos dump : WorldContainerDumps.getDumpSnapshot()) {
            if (dump.getCenter().distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            drawOutline(
                    poseStack,
                    vertexConsumer,
                    dump.getX() - cameraPos.x,
                    dump.getY() - cameraPos.y,
                    dump.getZ() - cameraPos.z,
                    dumpColor
            );
        }

        consumers.endBatch(RenderType.lines());
    }

    private static void drawOutline(
            PoseStack matrixStack,
            VertexConsumer vertexConsumer,
            double x,
            double y,
            double z,
            int argbColor
    ) {
        float alpha = ((argbColor >> 24) & 0xFF) / 255.0F;
        float red = ((argbColor >> 16) & 0xFF) / 255.0F;
        float green = ((argbColor >> 8) & 0xFF) / 255.0F;
        float blue = (argbColor & 0xFF) / 255.0F;

        LevelRenderer.renderVoxelShape(
                matrixStack,
                vertexConsumer,
                OUTLINE_SHAPE,
                x,
                y,
                z,
                red,
                green,
                blue,
                alpha,
                false
        );
    }
}
