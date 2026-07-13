package net.maxbel.takeitout.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class WorldContainerSourceRenderer {
    private static final float OUTLINE_ALPHA = 1.0F;
    private static final double OUTLINE_PADDING = 0.002D;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 128.0D * 128.0D;
    private static final AABB OUTLINE_SHAPE = new AABB(
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
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, WorldContainerSourceRenderer::onRenderLevelStage);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (!TakeitoutClient.RENDER_CONTAINER_SOURCES || client.level == null) {
            return;
        }

        Camera camera = event.getCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();

        VertexConsumer vertexConsumer = consumers.getBuffer(RenderType.lines());
        int color = TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR;
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
            if (Vec3.atCenterOf(source).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            LevelRenderer.renderLineBox(
                    event.getPoseStack(),
                    vertexConsumer,
                    OUTLINE_SHAPE.move(
                            source.getX() - cameraPos.x,
                            source.getY() - cameraPos.y,
                            source.getZ() - cameraPos.z
                    ),
                    red, green, blue, OUTLINE_ALPHA
            );
        }

        float dumpRed = 0xF9 / 255.0F;
        float dumpGreen = 0x73 / 255.0F;
        float dumpBlue = 0x16 / 255.0F;
        for (BlockPos dump : WorldContainerDumps.getDumpSnapshot()) {
            if (Vec3.atCenterOf(dump).distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            LevelRenderer.renderLineBox(
                    event.getPoseStack(),
                    vertexConsumer,
                    OUTLINE_SHAPE.move(
                            dump.getX() - cameraPos.x,
                            dump.getY() - cameraPos.y,
                            dump.getZ() - cameraPos.z
                    ),
                    dumpRed, dumpGreen, dumpBlue, OUTLINE_ALPHA
            );
        }
    }
}
