package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
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
        WorldRenderEvents.END.register(WorldContainerSourceRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!TakeitoutClient.RENDER_CONTAINER_SOURCES || client.world == null) {
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        if (!camera.isReady()) {
            return;
        }

        Vec3d cameraPos = camera.getPos();
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) {
            return;
        }

        VertexConsumer vertexConsumer = consumers.getBuffer(RenderLayer.getLines());

        for (BlockPos source : WorldContainerSources.getSourcesSnapshot()) {
            if (source.toCenterPos().squaredDistanceTo(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            drawOutline(
                    context.matrixStack(),
                    vertexConsumer,
                    source.getX() - cameraPos.x,
                    source.getY() - cameraPos.y,
                    source.getZ() - cameraPos.z,
                    TakeitoutClient.CONTAINER_SOURCE_OUTLINE_COLOR
            );
        }

        int dumpColor = 0xFFF97316;
        for (BlockPos dump : WorldContainerDumps.getDumpSnapshot()) {
            if (dump.toCenterPos().squaredDistanceTo(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            drawOutline(
                    context.matrixStack(),
                    vertexConsumer,
                    dump.getX() - cameraPos.x,
                    dump.getY() - cameraPos.y,
                    dump.getZ() - cameraPos.z,
                    dumpColor
            );
        }
    }

    private static void drawOutline(
            net.minecraft.client.util.math.MatrixStack matrixStack,
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

        WorldRenderer.drawShapeOutline(
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
