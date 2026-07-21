package com.catx.emioregen;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(EMIOreGeneration.MODID)
public class EMIOreGeneration {
    public static final String MODID = "emioregeneration";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EMIOreGeneration(IEventBus modEventBus, ModContainer modContainer) {
        // Register event handlers
        NeoForge.EVENT_BUS.register(this);
        // Inside EMIOreGeneration constructor:
        modEventBus.addListener((RegisterPayloadHandlersEvent event) -> {
            final PayloadRegistrar registrar = event.registrar("1.0.0");
            registrar.playToClient(
                    OreDataPayload.TYPE,
                    OreDataPayload.STREAM_CODEC,
                    (payload, context) -> {
                        // Store payload data in a ClientCache for EMI to read
                        context.enqueueWork(() -> {
                            // ClientOreCache.update(payload.rawOreData());
                        });
                    }
            );
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}