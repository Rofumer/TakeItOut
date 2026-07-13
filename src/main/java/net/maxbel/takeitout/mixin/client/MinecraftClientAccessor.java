package net.maxbel.takeitout.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Invoker("startUseItem")
    void takeitout$invokeDoItemUse();
}
