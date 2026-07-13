package net.maxbel.takeitout.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

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
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentParticles.class, WorldContainerSourceRenderer::onRenderLevelStage);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentParticles event) {
        Minecraft client = Minecraft.getInstance();
        if (!TakeitoutClient.RENDER_CONTAINER_SOURCES || client.level == null) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vec3 cameraPos = camera.position();
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();

        VertexConsumer vertexConsumer = consumers.getBuffer(RenderTypes.lines());
        int color = TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR;

        for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
            if (Vec3.atCenterOf(source).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            ShapeRenderer.renderShape(
                    event.getPoseStack(),
                    vertexConsumer,
                    OUTLINE_SHAPE,
                    source.getX() - cameraPos.x,
                    source.getY() - cameraPos.y,
                    source.getZ() - cameraPos.z,
                    color,
                    OUTLINE_ALPHA
            );
        }

        int dumpColor = 0xFFF97316;
        for (BlockPos dump : WorldContainerDumps.getDumpSnapshot()) {
            if (Vec3.atCenterOf(dump).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            ShapeRenderer.renderShape(
                    event.getPoseStack(),
                    vertexConsumer,
                    OUTLINE_SHAPE,
                    dump.getX() - cameraPos.x,
                    dump.getY() - cameraPos.y,
                    dump.getZ() - cameraPos.z,
                    dumpColor,
                    OUTLINE_ALPHA
            );
        }

        consumers.endBatch(RenderTypes.lines());
    }
}
