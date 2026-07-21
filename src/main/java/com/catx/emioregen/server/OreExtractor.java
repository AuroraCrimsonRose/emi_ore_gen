package com.catx.emioregen.server;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntryData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OreExtractor {

    public static List<OreEntryData> extractAll(MinecraftServer server) {
        List<OreEntryData> results = new ArrayList<>();
        Registry<PlacedFeature> placedRegistry = server.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);

        // Setup a serialization context so we can safely convert the worldgen math into JSON
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        for (Map.Entry<ResourceKey<PlacedFeature>, PlacedFeature> entry : placedRegistry.entrySet()) {
            ResourceLocation featureId = entry.getKey().location();
            PlacedFeature feature = entry.getValue();
            ConfiguredFeature<?, ?> configured = feature.feature().value();

            // Catch standard ores (Vanilla, Create, Mekanism, etc.)
            if (configured.config() instanceof OreConfiguration oreConfig) {
                String blockId = "minecraft:stone";
                if (!oreConfig.targetStates.isEmpty()) {
                    blockId = BuiltInRegistries.BLOCK.getKey(oreConfig.targetStates.get(0).state.getBlock()).toString();
                }

                int minY = -64;
                int maxY = 64;
                int count = oreConfig.size;

                // Safely extract the generation bounds by parsing the feature's internal JSON Codec
                try {
                    var encodedOpt = PlacedFeature.DIRECT_CODEC.encodeStart(ops, feature).result();
                    if (encodedOpt.isPresent() && encodedOpt.get().isJsonObject()) {
                        JsonObject encoded = encodedOpt.get().getAsJsonObject();

                        if (encoded.has("placement")) {
                            for (JsonElement modifierElem : encoded.getAsJsonArray("placement")) {
                                JsonObject modifier = modifierElem.getAsJsonObject();
                                if (!modifier.has("type")) continue;

                                String type = modifier.get("type").getAsString();

                                if (type.equals("minecraft:height_range")) {
                                    JsonObject heightObj = modifier.getAsJsonObject("height");
                                    minY = parseVerticalAnchor(heightObj.get("min_inclusive"));
                                    maxY = parseVerticalAnchor(heightObj.get("max_inclusive"));
                                } else if (type.equals("minecraft:count") && modifier.has("count")) {
                                    if (modifier.get("count").isJsonPrimitive()) {
                                        count = modifier.get("count").getAsInt();
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    EMIOreGeneration.LOGGER.debug("Could not parse JSON for feature {}, falling back to defaults.", featureId);
                }

                // Determine Dimension/Biome roughly based on the feature registry name or block name
                // Determine Dimension/Biome roughly based on the feature name or blocks
                String dimension = "Overworld";
                String path = featureId.getPath().toLowerCase();
                String bId = blockId.toLowerCase();

                if (path.contains("nether") || bId.contains("nether")) dimension = "Nether";
                else if (path.contains("end") || bId.contains("end")) dimension = "The End";
                else if (featureId.getNamespace().equals("northstar")) {
                    if (path.contains("moon") || bId.contains("moon")) dimension = "Moon";
                    else if (path.contains("mars") || bId.contains("mars")) dimension = "Mars";
                    else if (path.contains("venus") || bId.contains("venus")) dimension = "Venus";
                    else dimension = "Northstar Space";
                }

                int avgY = (minY + maxY) / 2;
                int variance = Math.abs(maxY - minY) / 2;

                results.add(new OreEntryData(
                        blockId, featureId.getPath(), minY, maxY, avgY, variance, count, dimension
                ));
            }
        }

        // Add GregTech Veins dynamically
        if (ModList.get().isLoaded("gtceu")) {
            try {
                results.addAll(GregTechCompat.extractGTOres(server));
            } catch (Exception e) {
                EMIOreGeneration.LOGGER.error("Failed to extract GregTech veins", e);
            }
        }

        return results;
    }

    // Helper method to parse Minecraft's Y-level logic from the JSON
    private static int parseVerticalAnchor(JsonElement anchorElem) {
        if (anchorElem == null) return 0;
        if (anchorElem.isJsonObject()) {
            JsonObject obj = anchorElem.getAsJsonObject();
            if (obj.has("absolute")) return obj.get("absolute").getAsInt();
            if (obj.has("above_bottom")) return -64 + obj.get("above_bottom").getAsInt();
            if (obj.has("below_top")) return 320 - obj.get("below_top").getAsInt();
        } else if (anchorElem.isJsonPrimitive()) {
            return anchorElem.getAsInt(); // Flat integer fallback
        }
        return 0;
    }
}