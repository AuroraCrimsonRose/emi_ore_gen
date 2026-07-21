package com.catx.emioregen;

import com.catx.emioregen.client.ClientOreCache;
import com.catx.emioregen.data.OreEntryData;
import com.catx.emioregen.network.OreDataPayload;
import com.catx.emioregen.server.OreExtractor;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod(EMIOreGeneration.MODID)
public class EMIOreGeneration {
    public static final String MODID = "emioregeneration";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final List<OreEntryData> EXTRACTED_ORES = new ArrayList<>();

    public EMIOreGeneration(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MODID);
        registrar.playToClient(
                OreDataPayload.TYPE,
                OreDataPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        ClientOreCache.update(payload.oreEntries());
                        LOGGER.info("Client cached {} ores for EMI!", payload.oreEntries().size());

                        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                            dev.emi.emi.runtime.EmiReloadManager.reload();
                        }
                    });
                }
        );
    }

    private void onServerStarted(ServerStartedEvent event) {
        EXTRACTED_ORES.clear();
        EXTRACTED_ORES.addAll(OreExtractor.extractAll(event.getServer()));
        LOGGER.info("Successfully extracted {} ore entries.", EXTRACTED_ORES.size());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new OreDataPayload(new ArrayList<>(EXTRACTED_ORES)));
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}