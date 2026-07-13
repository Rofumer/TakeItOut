package net.maxbel.takeitout;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod("takeitout")
public class TakeItOutNeoForge {
    public TakeItOutNeoForge(IEventBus modEventBus) {
        Takeitout.init(modEventBus);

        if (FMLEnvironment.dist.isClient()) {
            net.maxbel.takeitout.client.TakeitoutClient.init(modEventBus);
        }
    }
}
