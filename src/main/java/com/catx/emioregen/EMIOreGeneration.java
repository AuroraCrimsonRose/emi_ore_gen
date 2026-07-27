package com.catx.emioregen;

import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.network.OreDataPayload;
import com.catx.emioregen.server.WorldGenIndexer;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

    /** NeoForge caps a custom payload at roughly 1 MiB; this leaves headroom for framing. */
    private static final int PAYLOAD_LIMIT_BYTES = 900_000;

    /** Server-side index, rebuilt on server start. Empty on a client that has not joined a world. */
    private static final List<OreEntry> INDEX = new ArrayList<>();

    public EMIOreGeneration(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).optional();
        registrar.playToClient(
                OreDataPayload.TYPE,
                OreDataPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadHandler.accept(payload)));
    }

    private void onServerStarted(ServerStartedEvent event) {
        long start = System.nanoTime();

        INDEX.clear();
        INDEX.addAll(WorldGenIndexer.index(event.getServer()));

        long millis = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.info("Indexed {} ore occurrences in {} ms", INDEX.size(), millis);

        if (INDEX.isEmpty()) {
            LOGGER.warn("No ore occurrences found. Nothing will show up in EMI's ore generation tab.");
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        INDEX.clear();
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (INDEX.isEmpty()) {
            return;
        }

        OreDataPayload payload = new OreDataPayload(List.copyOf(INDEX));

        // NeoForge rejects custom payloads over ~1 MiB, and a heavily modded pack can get close,
        // so this is checked rather than assumed. Failing here is far easier to diagnose than a
        // disconnect during login.
        int bytes = payload.estimateCompressedBytes();
        if (bytes > PAYLOAD_LIMIT_BYTES) {
            // Biome lists are almost all of the weight in a big pack. Dropping them costs the
            // biome cycler but keeps every ore, depth and rarity, which is the bulk of the value.
            LOGGER.warn("Ore index is {} KiB compressed; dropping biome detail to fit the packet",
                    bytes / 1024);

            List<OreEntry> stripped = new ArrayList<>(INDEX.size());
            for (OreEntry entry : INDEX) {
                stripped.add(entry.withoutBiomes());
            }
            payload = new OreDataPayload(stripped);
            bytes = payload.estimateCompressedBytes();

            if (bytes > PAYLOAD_LIMIT_BYTES) {
                LOGGER.error("Ore index is still {} KiB after stripping biomes. Skipping sync for {}.",
                        bytes / 1024, serverPlayer.getGameProfile().getName());
                return;
            }
        }

        LOGGER.debug("Sending {} ore occurrences ({} KiB) to {}",
                INDEX.size(), bytes / 1024, serverPlayer.getGameProfile().getName());
        PacketDistributor.sendToPlayer(serverPlayer, payload);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
