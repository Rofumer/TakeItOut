package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Takeitout implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPayload {
        public static final CustomPayload.Id<GetShulkerStackPayload> ID = new CustomPayload.Id<>(Identifier.of("takeitout", "getstack"));
        public static final PacketCodec<RegistryByteBuf, GetShulkerStackPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.INTEGER,
                GetShulkerStackPayload::slot,
                PacketCodecs.INTEGER,
                GetShulkerStackPayload::shulker,
                PacketCodecs.BOOLEAN,
                GetShulkerStackPayload::singleItemMode,
                GetShulkerStackPayload::new
        );

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
                int slotInShulker = payload.slot();
                int shulkerSlot = payload.shulker();
                boolean singleItemMode = payload.singleItemMode();
                int selectedSlot = player.getInventory().getSelectedSlot();

                LOGGER.debug(
                        "GetShulkerStack request: player={}, shulkerSlot={}, shulkerInnerSlot={}, handSlot={}, singleItemMode={}",
                        player.getName().getString(),
                        shulkerSlot,
                        slotInShulker,
                        selectedSlot,
                        singleItemMode
                );
                if (player.getInventory().getEmptySlot()!=-1) {
                    ItemStack stackInShulker = (player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().get(slotInShulker);
                    if (stackInShulker == null || stackInShulker.isEmpty()) {
                        LOGGER.warn(
                                "GetShulkerStack aborted: player={}, reason=empty_stack, shulkerSlot={}, shulkerInnerSlot={}",
                                player.getName().getString(),
                                shulkerSlot,
                                slotInShulker
                        );
                        return;
                    }

                    ItemStack shulker = player.getInventory().getStack(shulkerSlot);
                    List<ItemStack> itemStacks = DefaultedList.of();
                    itemStacks.addAll(shulker.get(DataComponentTypes.CONTAINER).stream().toList());
                    ItemStack stackToHand = stackInShulker.copy();
                    if (singleItemMode && stackInShulker.getCount() > 1) {
                        stackToHand.setCount(1);
                        ItemStack remaining = stackInShulker.copy();
                        remaining.decrement(1);
                        itemStacks.set(slotInShulker, remaining);
                    } else {
                        itemStacks.set(slotInShulker, ItemStack.EMPTY);
                    }
                    shulker.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                    //player.getInventory().setStack(player.getInventory().getEmptySlot(), player.getInventory().getSelectedStack());
                    ItemStack handBefore = player.getStackInHand(Hand.MAIN_HAND).copy();
                    int movedToSlot = -1;
                    if (!handBefore.isEmpty()) {
                        movedToSlot = player.getInventory().getEmptySlot();
                        player.getInventory().setStack(movedToSlot, player.getStackInHand(Hand.MAIN_HAND));
                    }
                    player.setStackInHand(Hand.MAIN_HAND, stackToHand);
                    player.getInventory().setStack(selectedSlot, stackToHand.copy());
                    player.getInventory().markDirty();
                    player.playerScreenHandler.sendContentUpdates();
                    LOGGER.debug(
                            "GetShulkerStack success(empty-slot path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}, replacedHandItem={}, movedFromHandToSlot={}, handSlot={}, singleItemMode={}",
                            player.getName().getString(),
                            stackToHand,
                            shulkerSlot,
                            slotInShulker,
                            handBefore,
                            movedToSlot,
                            selectedSlot,
                            singleItemMode
                    );

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
                    for (int i = Math.min(36, player.getInventory().size())-1 ; i >=0; --i) {
                        item = player.getInventory().getStack(i);
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
                            ItemStack stackInShulker = (player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().get(slotInShulker);
                            if (stackInShulker == null || stackInShulker.isEmpty()) {
                                LOGGER.warn(
                                        "GetShulkerStack aborted: player={}, reason=empty_stack_no_free_slot, shulkerSlot={}, shulkerInnerSlot={}",
                                        player.getName().getString(),
                                        shulkerSlot,
                                        slotInShulker
                                );
                                return;
                            }
                            ItemStack shulker = player.getInventory().getStack(shulkerSlot);
                            List<ItemStack> itemStacks = DefaultedList.of();
                            itemStacks.addAll(shulker.get(DataComponentTypes.CONTAINER).stream().toList());
                            ItemStack stackToHand = stackInShulker.copy();
                            if (singleItemMode && stackInShulker.getCount() > 1) {
                                stackToHand.setCount(1);
                                ItemStack remaining = stackInShulker.copy();
                                remaining.decrement(1);
                                itemStacks.set(slotInShulker, remaining);
                            } else {
                                itemStacks.set(slotInShulker, item);
                            }
                            shulker.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                            //player.getInventory().setStack(i, player.getInventory().getSelectedStack());
                            ItemStack handBefore = player.getStackInHand(Hand.MAIN_HAND).copy();
                            player.getInventory().setStack(i, player.getStackInHand(Hand.MAIN_HAND));
                            player.setStackInHand(Hand.MAIN_HAND, stackToHand);
                            player.getInventory().setStack(selectedSlot, stackToHand.copy());
                            player.getInventory().markDirty();
                            player.playerScreenHandler.sendContentUpdates();
                            LOGGER.debug(
                                    "GetShulkerStack success(replace path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}, replacedInventorySlot={}, replacedItem={}, oldHandItem={}, handSlot={}, singleItemMode={}",
                                    player.getName().getString(),
                                    stackToHand,
                                    shulkerSlot,
                                    slotInShulker,
                                    i,
                                    item,
                                    handBefore,
                                    selectedSlot,
                                    singleItemMode
                            );
                            break;
                        }
                    }
                }
            });
        });
    }
}
