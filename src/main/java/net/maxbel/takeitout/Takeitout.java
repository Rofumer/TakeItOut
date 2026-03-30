package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
//import net.minecraft.item.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.util.List;

public class Takeitout implements ModInitializer {
    public record GetShulkerStackPayload(int slot, int shulker) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetShulkerStackPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "getstack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GetShulkerStackPayload> CODEC = StreamCodec.composite(ByteBufCodecs.INT, GetShulkerStackPayload::slot, ByteBufCodecs.INT, GetShulkerStackPayload::shulker, GetShulkerStackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                int slotInShulker = payload.slot();
                int shulkerSlot = payload.shulker();
                if (player.getInventory().getFreeSlot()!=-1) {
                    ItemStack stackInShulker = (player.getInventory().getItem(shulkerSlot).get(DataComponents.CONTAINER)).stream().toList().get(slotInShulker);
                    if (stackInShulker == null || stackInShulker.isEmpty()) {
                        return;
                    }

                    ItemStack shulker = player.getInventory().getItem(shulkerSlot);
                    List<ItemStack> itemStacks = NonNullList.create();
                    itemStacks.addAll(shulker.get(DataComponents.CONTAINER).stream().toList());
                    itemStacks.set(slotInShulker, ItemStack.EMPTY);
                    shulker.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(itemStacks));
                    //player.getInventory().setStack(player.getInventory().getEmptySlot(), player.getInventory().getSelectedStack());
                    player.getInventory().setItem(player.getInventory().getFreeSlot(), player.getItemInHand(InteractionHand.MAIN_HAND));
                    player.setItemInHand(InteractionHand.MAIN_HAND, stackInShulker);

                    /*ItemStack singleItem = stackInShulker.copy();
                    singleItem.setCount(1);

                    ItemStack remainingStack = stackInShulker.copy();
                    if (stackInShulker.getCount() > 1) {
                        remainingStack.setCount(stackInShulker.getCount() - 1);
                    } else {
                        remainingStack = ItemStack.EMPTY; // Пустой стек
                    }

                    ItemStack shulker = player.getInventory().getStack(shulkerSlot);
                    List<ItemStack> itemStacks = DefaultedList.of();
                    itemStacks.addAll(shulker.get(DataComponentTypes.CONTAINER).stream().toList());
                    //itemStacks.set(slotInShulker, ItemStack.EMPTY);
                    itemStacks.set(slotInShulker, remainingStack);
                    shulker.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                    player.getInventory().setStack(player.getInventory().getEmptySlot(), player.getInventory().getSelectedStack());
                    //player.setStackInHand(Hand.MAIN_HAND, stackInShulker);
                    player.setStackInHand(Hand.MAIN_HAND, singleItem);*/
                }
                else
                {
                    ItemStack item;
                    for (int i = Math.min(36, player.getInventory().getContainerSize())-1 ; i >=0; --i) {
                        item = player.getInventory().getItem(i);
                        ///////if(!(((BlockItem)item.getItem()).getBlock() instanceof EnderChestBlock || item.getItem() instanceof HoeItem || item.getItem() instanceof AxeItem || item.getItem() instanceof ShovelItem) && (item.getItem() instanceof BlockItem && !(((BlockItem)item.getItem()).getBlock() instanceof ShulkerBoxBlock)))
                        if (
                                !(item.getItem() instanceof HoeItem
                                        || item.getItem() instanceof AxeItem
                                        || item.getItem() instanceof ShovelItem)
                                        && (
                                        item.getItem() instanceof BlockItem
                                                && !(((BlockItem) item.getItem()).getBlock() instanceof ShulkerBoxBlock)
                                                && !(((BlockItem) item.getItem()).getBlock() instanceof EnderChestBlock)
                                )
                        )
                        {
                            ItemStack stackInShulker = (player.getInventory().getItem(shulkerSlot).get(DataComponents.CONTAINER)).stream().toList().get(slotInShulker);
                            if (stackInShulker == null || stackInShulker.isEmpty()) {
                                return;
                            }
                            ItemStack shulker = player.getInventory().getItem(shulkerSlot);
                            List<ItemStack> itemStacks = NonNullList.create();
                            itemStacks.addAll(shulker.get(DataComponents.CONTAINER).stream().toList());
                            itemStacks.set(slotInShulker, item);
                            shulker.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(itemStacks));
                            //player.getInventory().setStack(i, player.getInventory().getSelectedStack());
                            player.getInventory().setItem(i, player.getItemInHand(InteractionHand.MAIN_HAND));
                            player.setItemInHand(InteractionHand.MAIN_HAND, stackInShulker);
                            break;
                        }
                    }
                }
            });
        });
    }
}