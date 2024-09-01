package net.maxbel.takeitout.client;

import fi.dy.masa.litematica.util.RayTraceUtils;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.util.hit.BlockHitResult;


import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;

public class TakeitoutClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
    }
    protected static int getSlotWithItem(ClientPlayerEntity player, Item item) {
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < inventory.main.size(); ++i) {
            if (inventory.main.get(i).isOf(item)) return i;
            if (!inventory.main.get(i).isEmpty() && ItemStack.areItemsEqual(inventory.main.get(i), item.getDefaultStack())) {
                return i;
            }
        }

        return -1;
    }

    public static boolean onGameTick() {

        MinecraftClient mc = MinecraftClient.getInstance();

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();

        /*if (!actionHandler.acceptsActions()) return false;

        if (worldSchematic == null) return false;

        if (!LitematicaMixinMod.PRINT_MODE.getBooleanValue() && !LitematicaMixinMod.PRINT.getKeybind().isPressed())
            return false;*/

        PlayerAbilities abilities = mc.player.getAbilities();
        if (!abilities.allowModifyWorld)
            return false;

        //import fi.dy.masa.litematica.util.WorldUtils;
        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
        if (result != null) {
            if (result.getBlockPos() != null) {
                SchematicBlockState state = new SchematicBlockState(mc.player.getWorld(), worldSchematic, result.getBlockPos());

                if (!state.targetState.isAir()) {

                    mc.player.sendMessage(Text.of(Text.of(state.targetState.getBlock().toString())+"777"));




                    if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {


                        //if (player.getInventory().getEmptySlot() != -1) {
                        //player.sendMessage(Text.of(Math.random() + state.targetState.getBlock().toString()));
                        //player.sendMessage(Text.of(Math.random() + "slot found!!!"));
                        //player.sendMessage(Text.of(Math.random() + state.currentState.getBlock().toString()));
                        //if (player.getInventory().getEmptySlot() != -1) {
                        //    player.getInventory().swapSlotWithHotbar(player.getInventory().getEmptySlot());
                        //}

                        //player.getInventory().setItemInHand(new ItemStack(Material.AIR));

                        //MinecraftClient CLIENT = MinecraftClient.getInstance();

                        /*final int source = 1;
                        final int destination = 2;

                        CLIENT.interactionManager.clickSlot(
                                CLIENT.player.playerScreenHandler.syncId,
                                8,
                                8,
                                SlotActionType.SWAP,
                                CLIENT.player
                        );*/

                        //MinecraftClient.getInstance().interactionManager.clickSlot(player.currentScreenHandler.syncId, 8 , 8, SlotActionType.SWAP, player);

                        //player.getInventory().swapSlotWithHotbar();

                        //MinecraftClient.getInstance().getco
                        //getConnection().send(new ServerboundCustomPayloadPacket(new SwapItemPayload(inventorySlot, slot)));


                        //player.getInventory().swapSlotWithHotbar(player.getInventory().getEmptySlot());

                        //swapSlots(player,player.getInventory().getEmptySlot(),8);
                        //swapItemToHand(player, Hand.MAIN_HAND ,player.getInventory().getEmptySlot());

                        //swapItemToHand(mc.player, Hand.MAIN_HAND, slot);
                        //player.sendMessage(Text.of(player.getInventory().getEmptySlot() + "slot found!!!"));
                        //swapSlots(player,getRandomNumber(36,45),getRandomNumber(36,45));
                        //!int slot = findEmptySlot(mc.player.currentScreenHandler, Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45));
                        //!mc.player.sendMessage(Text.of(slot + "slot found!!!"));
                        //!if (slot != -1) {
                        //!    swapItemToHand(mc.player, Hand.MAIN_HAND, slot);
                        mc.player.sendMessage(Text.of( "slot found!!!+1!!!!!!@@@@"));
                        WorldUtils.doSchematicWorldPickBlock(false, mc);
                        return true;
                        //!}

                        //}
                    }
                }
            }
        }
        return false;
    }
}
