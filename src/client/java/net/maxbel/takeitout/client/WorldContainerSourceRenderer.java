package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public class WorldContainerSourceRenderer {
    private static final float OUTLINE_ALPHA = 1.0F;
    private static final double OUTLINE_PADDING = 0.002D;
    private static final double MAX_RENDER_DISTANCE_SQUARED = 128.0D * 128.0D;
    private static final VoxelShape OUTLINE_SHAPE = VoxelShapes.cuboid(
            -OUTLINE_PADDING,
            -OUTLINE_PADDING,
            -OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING,
            1.0D + OUTLINE_PADDING
    );

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!TakeitoutClient.RENDER_CONTAINER_SOURCES || client.world == null || WorldContainerSources.size() == 0) {
                return;
            }

            Camera camera = client.gameRenderer.getCamera();
            if (!camera.isReady()) {
                return;
            }

            Vec3d cameraPos = camera.getCameraPos();
            VertexConsumerProvider consumers = context.consumers();
            if (consumers == null) {
                return;
            }

            VertexConsumer vertexConsumer = consumers.getBuffer(RenderLayers.lines());

            for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
                if (source.toCenterPos().squaredDistanceTo(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                    continue;
                }

                VertexRendering.drawOutline(
                        context.matrices(),
                        vertexConsumer,
                        OUTLINE_SHAPE,
                        source.getX() - cameraPos.x,
                        source.getY() - cameraPos.y,
                        source.getZ() - cameraPos.z,
                        TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR,
                        OUTLINE_ALPHA
                );
            }
        });
    }
}
