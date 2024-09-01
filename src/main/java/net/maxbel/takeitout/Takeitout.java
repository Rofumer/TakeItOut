package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.collection.DefaultedList;
import java.util.Objects;
import java.util.UUID;

public class Takeitout implements ModInitializer {

    public record GetShulkerStackPayload(int slot, int shulker) implements CustomPayload {
        public static final CustomPayload.Id<GetShulkerStackPayload> ID = new CustomPayload.Id<>(Identifier.of("pickshulker", "getstack"));
        public static final PacketCodec<RegistryByteBuf, GetShulkerStackPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, GetShulkerStackPayload::slot, PacketCodecs.INTEGER, GetShulkerStackPayload::shulker,GetShulkerStackPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {

        PayloadTypeRegistry.playC2S().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) -> {
            context.server().execute(() -> {

                ServerPlayerEntity player = context.player();

                int slotInShulker=payload.slot();
                int shulkerSlot = payload.shulker();
                if(shulkerSlot == -1) return;
                //if (shulkerSlot != -1 && (slotInShulker = Util.getSlotWithStack((Inventory) (shulkerInv = ShulkerUtils.getInventoryFromShulker((ItemStack) player.getInventory().getStack(shulkerSlot))), stack)) != -1) {
                if (shulkerSlot != -1 && slotInShulker != -1) {
                    int hotbarSlot;
                    //shulkerInv = ShulkerUtils.getInventoryFromShulker((ItemStack) player.getInventory().getStack(shulkerSlot));
                    //-//player.getInventory().selectedSlot = hotbarSlot = Util.getHotBarSlot(player.getInventory(), shulkerSlot);
                    /*try {
                        ((net.minecraft.client.network.ClientPlayerEntity) player).networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));
                    }
                    catch(Exception e){
                        e.printStackTrace();
                    }*/



                    //ItemStack stackInShulker = shulkerInv.removeStack(slotInShulker);
                    //ItemStack stackInShulker=shulkerInv.getStack(slotInShulker);

                    //DefaultedList shulkerStacks = DefaultedList.of();


                    //item = item.removeStack(slotInShulker);



                    //shulkerStacks.addAll(Objects.requireNonNull(player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList());

                    //MinecraftClient.getInstance().player.sendMessage(Text.of(String.valueOf(slotInShulker)+Text.of("slot  in shulker"+ Text.of(String.valueOf(shulkerSlot)))));

                    if((player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().size()<=slotInShulker) {
                        //MinecraftClient.getInstance().player.sendMessage(Text.of("Big index-"+slotInShulker));

                        if((player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().isEmpty())
                        {
                            //MinecraftClient.getInstance().player.sendMessage(Text.of("Empty Shulker"));
                        }

                        return;
                    }




                    ItemStack stackInShulker = (player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().get(slotInShulker);

                    if(stackInShulker == null || stackInShulker.isEmpty()){

                        //MinecraftClient.getInstance().player.sendMessage(Text.of("Stack is null or air"));

                        return;}
                    //MinecraftClient.getInstance().player.sendMessage(Text.of("uuu"+ Text.of(stackInShulker.toString())));
                    //public static void setItem(ItemStack shulker, NonNullList<ItemStack> items) {
                    // spotless:off
                    //#if MC <= 12004
                    //$$  CompoundTag tag = BlockItem.getBlockEntityData(shulker);
                    //$$  CompoundTag rootTag = ContainerHelper.saveAllItems(tag != null ? tag : new CompoundTag(), items);
                    //$$  BlockItem.setBlockEntityData(shulker, BlockEntityType.SHULKER_BOX, rootTag);
                    //#else

                    //DefaultedList itemStacks = DefaultedList.of();

                    ItemStack item = player.getInventory().getStack(shulkerSlot);

                    //if (MinecraftClient.getInstance().player != null) {
                        //MinecraftClient.getInstance().player.sendMessage(Text.of("---"+ Text.of(item.toString())));
                    //}

                    DefaultedList itemStacks = DefaultedList.of();


                    //item = item.removeStack(slotInShulker);

                    itemStacks.addAll(Objects.requireNonNull(item.get(DataComponentTypes.CONTAINER)).stream().toList());

                    /*if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(Text.of(itemStacks.toString()));
                    }*/


                    try {
                        itemStacks.set(slotInShulker, ItemStack.EMPTY);
                    }
                    catch(Exception e){
                        //MinecraftClient.getInstance().player.sendMessage(Text.of(e.getMessage()));
                        //e.printStackTrace();
                    }

                    //itemStacks.remove(slotInShulker);

                    item.set(net.minecraft.component.DataComponentTypes.CONTAINER,net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                    ///////item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Lorem ipsum"));
                    ///////item.apply(DataComponentTypes.CUSTOM_NAME, item.getName(), current -> current.copy().formatted(Formatting.RED));
                    /*try {
                        //MinecraftClient.getInstance().player.getInventory().selectedSlot, ClickType.SWAP, minecraft.player);
                    }
                    catch(Exception e){
                        e.printStackTrace();
                    }
                    //stackInShulker.remove(DataComponentTypes.CONTAINER);
                    //MinecraftClient.getInstance().player.sendMessage(Text.of("Stack Removed: " + slotInShulker + " " + stackInShulker.toString()));
                    if (!player.getInventory().getStack(hotbarSlot).isEmpty()) {
                        if (!player.getInventory().getStack(hotbarSlot).isEmpty()) {
                            InventoryHelper.insertStack((Inventory) player.getInventory(), hotbarSlot, (Inventory) player.getInventory(), hotbarSlot);
                        }
                        if (!player.getInventory().getStack(hotbarSlot).isEmpty() && !Util.isShulkerItem(player.getInventory().getStack(hotbarSlot))) {
                            ItemStack result = ShulkerUtils.insertIntoShulker((SimpleInventory) shulkerInv, (ItemStack) player.getInventory().getStack(hotbarSlot), (PlayerEntity) player);
                            player.getInventory().setStack(hotbarSlot, result);
                        }
                        if (!player.getInventory().getStack(hotbarSlot).isEmpty()) {
                            player.dropStack(player.getInventory().removeStack(hotbarSlot));
                        }
                    }*/
                    //-//player.getInventory().setStack(hotbarSlot, stackInShulker);
                    /*if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(Text.of(String.valueOf(player.getInventory().selectedSlot)));
                    }
                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(Text.of(stackInShulker.toString()));
                    }*/
                    int emptySlot = player.getInventory().getEmptySlot();
                    ItemStack mainhandStack = player.getInventory().getMainHandStack();
                    //player.getInventory().setStack( emptySlot, stackInShulker);
                    player.getInventory().setStack( emptySlot, mainhandStack);
                    player.setStackInHand(Hand.MAIN_HAND,stackInShulker);
                    //player.getInventory().swapSlotWithHotbar(emptySlot);
                    //swapItemToHand(player, Hand.MAIN_HAND, emptySlot);
                    //player.getInventory().swapSlotWithHotbar();

                    //ServerPlayNetworking.createS2CPacket(new CustomIngredientPayloadS2C()).

                }

            });
        });

    }
}
