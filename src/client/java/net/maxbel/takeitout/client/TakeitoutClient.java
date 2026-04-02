package net.maxbel.takeitout.client;

import org.slf4j.Logger;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.LoggerFactory;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public class TakeitoutClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TakeItOut");
    private static KeyMapping keyBinding;
    public static boolean AUTOTAKEOUT;
    public static ItemStack awaitingStack;

    private static boolean pickWasDownLastTick = false;
    private static boolean useWasDownLastTick = false;

    @Override
    public void onInitializeClient() {
        AUTOTAKEOUT = false;
        awaitingStack = ItemStack.EMPTY;

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("takeitout", "takeitout")
        );

        keyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.takeitout.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                category
        ));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                useWasDownLastTick = false;
                return;
            }

            boolean useDown = client.options.keyUse.isDown();
            if (useDown && !useWasDownLastTick) {
                tryPickFromSchematicOnUse(client);
            }
            useWasDownLastTick = useDown;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                if (client.player == null) {
                    continue;
                }

                if (AUTOTAKEOUT) {
                    client.player.sendSystemMessage(Component.translatable("message.takeitout.off"));
                    AUTOTAKEOUT = false;
                    awaitingStack = ItemStack.EMPTY;
                } else {
                    client.player.sendSystemMessage(Component.translatable("message.takeitout.on"));
                    AUTOTAKEOUT = true;
                    awaitingStack = ItemStack.EMPTY;
                }
            }

            if (client.player == null) {
                pickWasDownLastTick = false;
                return;
            }

            boolean pickDown = client.options.keyPickItem.isDown();

            if (pickDown && !pickWasDownLastTick) {
                System.out.println("[TakeItOut] PICK DETECTED");
                tryPickFromShulker(client);
            }

            pickWasDownLastTick = pickDown;
        });
    }

    public static int getSlotWithItem(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);

            if (stack.is(item)) {
                return i;
            }

            if (!stack.isEmpty() && ItemStack.isSameItem(stack, item.getDefaultInstance())) {
                return i;
            }
        }

        return -1;
    }

    private static void tryPickFromShulker(Minecraft mc) {
        LOGGER.info("[CLIENT] tryPickFromShulker called");

        if (!AUTOTAKEOUT || !awaitingStack.isEmpty()) {
            LOGGER.info("[CLIENT] blocked: AUTOTAKEOUT={}, awaitingStackEmpty={}", AUTOTAKEOUT, awaitingStack.isEmpty());
            return;
        }

        if (mc.player == null || mc.level == null) {
            LOGGER.info("[CLIENT] blocked: no player or level");
            return;
        }

        if (mc.player.getAbilities().instabuild) {
            LOGGER.info("[CLIENT] blocked: creative mode");
            return;
        }

        if (!(mc.hitResult instanceof BlockHitResult blockHitResult)) {
            LOGGER.info("[CLIENT] blocked: hitResult={}", mc.hitResult);
            return;
        }

        BlockState blockState = mc.level.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack stack = block.asItem().getDefaultInstance();

        LOGGER.info("[CLIENT] target block={}, stack={}", block, stack);

        if (stack.isEmpty()) {
            LOGGER.info("[CLIENT] blocked: target stack is empty");
            return;
        }

        Inventory inventory = mc.player.getInventory();
        int slot = inventory.findSlotMatchingItem(stack);
        LOGGER.info("[CLIENT] direct inventory slot={}", slot);

        if (slot != -1) {
            LOGGER.info("[CLIENT] blocked: already in inventory");
            return;
        }

        int shulker = Util.getShulkerWithStack(inventory, stack);
        LOGGER.info("[CLIENT] shulker slot={}", shulker);

        if (shulker == -1) {
            LOGGER.info("[CLIENT] blocked: no shulker with stack found");
            return;
        }

        int inner = Util.getSlotWithStack(
                ItemStackInventory.getInventoryFromShulker(inventory.getItem(shulker)),
                stack
        );
        LOGGER.info("[CLIENT] inner slot={}", inner);

        if (inner == -1) {
            LOGGER.info("[CLIENT] blocked: inner slot not found");
            return;
        }

        boolean canSend = ClientPlayNetworking.canSend(Takeitout.GetShulkerStackPayload.ID);
        LOGGER.info("[CLIENT] canSend={}", canSend);

        if (!canSend) {
            LOGGER.info("[CLIENT] blocked: networking channel unavailable");
            return;
        }

        LOGGER.info("[CLIENT] sending packet: inner={}, shulker={}", inner, shulker);
        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulker));
    }

    private static void tryPickFromSchematicOnUse(Minecraft mc) {
        if (!AUTOTAKEOUT || mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }

        if (Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            return;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }

        BlockHitResult schematicHit = RayTraceUtils.traceToSchematicWorld(mc.player, 5.0D, true, true);
        if (schematicHit == null || schematicHit.getBlockPos() == null) {
            return;
        }

        SchematicBlockState st = new SchematicBlockState(mc.level, schematicWorld, schematicHit.getBlockPos());
        if (st.targetState == null || st.targetState.isAir()) {
            return;
        }

        WorldUtils.doSchematicWorldPickBlock(true, mc);
    }

    public static boolean onGameTick() {
        if (!AUTOTAKEOUT || !awaitingStack.isEmpty()) {
            return false;
        }

        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
        } catch (ClassNotFoundException e) {
            return false;
        }

        try {
            Class.forName("me.aleksilassila.litematica.printer.Printer");
            return false;
        } catch (ClassNotFoundException e) {
            // ignore
        }

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }

        Abilities abilities = mc.player.getAbilities();
        if (!abilities.mayBuild) {
            return false;
        }

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
        if (result == null) {
            return false;
        }

        SchematicBlockState state = new SchematicBlockState(
                mc.player.level(),
                worldSchematic,
                result.getBlockPos()
        );

        if (state.targetState != null && !state.targetState.isAir()) {
            if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                return true;
            }
        }

        return false;
    }
}
