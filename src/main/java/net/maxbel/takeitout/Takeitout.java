package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.List;

public class Takeitout implements ModInitializer {
    public record GetShulkerStackPayload(int slot, int shulker) implements CustomPayload {
        public static final CustomPayload.Id<GetShulkerStackPayload> ID = new CustomPayload.Id<>(Identifier.of("takeitout", "getstack"));
        public static final PacketCodec<RegistryByteBuf, GetShulkerStackPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, GetShulkerStackPayload::slot, PacketCodecs.INTEGER, GetShulkerStackPayload::shulker, GetShulkerStackPayload::new);

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
                if (player.getInventory().getEmptySlot()!=-1) {
                    ItemStack stackInShulker = (player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().get(slotInShulker);
                    if (stackInShulker == null || stackInShulker.isEmpty()) {
                        return;
                    }
                    ItemStack shulker = player.getInventory().getStack(shulkerSlot);
                    List<ItemStack> itemStacks = DefaultedList.of();
                    itemStacks.addAll(shulker.get(DataComponentTypes.CONTAINER).stream().toList());
                    itemStacks.set(slotInShulker, ItemStack.EMPTY);
                    shulker.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                    player.getInventory().setStack(player.getInventory().getEmptySlot(), player.getInventory().getMainHandStack());
                    player.setStackInHand(Hand.MAIN_HAND, stackInShulker);
                }
                else
                {
                    ItemStack item;
                    for (int i = Math.min(36, player.getInventory().size())-1 ; i >=0; --i) {
                        item = player.getInventory().getStack(i);
                        if(!(item.getItem() instanceof ToolItem) && (item.getItem() instanceof BlockItem && !(((BlockItem)item.getItem()).getBlock() instanceof ShulkerBoxBlock)))
                        {
                            ItemStack stackInShulker = (player.getInventory().getStack(shulkerSlot).get(DataComponentTypes.CONTAINER)).stream().toList().get(slotInShulker);
                            if (stackInShulker == null || stackInShulker.isEmpty()) {
                                return;
                            }
                            ItemStack shulker = player.getInventory().getStack(shulkerSlot);
                            List<ItemStack> itemStacks = DefaultedList.of();
                            itemStacks.addAll(shulker.get(DataComponentTypes.CONTAINER).stream().toList());
                            itemStacks.set(slotInShulker, item);
                            shulker.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(itemStacks));
                            player.getInventory().setStack(i, player.getInventory().getMainHandStack());
                            player.setStackInHand(Hand.MAIN_HAND, stackInShulker);
                            break;
                        }
                    }
                }
            });
        });
    }
}