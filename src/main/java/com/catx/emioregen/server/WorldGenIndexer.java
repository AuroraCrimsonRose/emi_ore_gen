package com.catx.emioregen.server;

import com.catx.emioregen.Config;
import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SourceKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the server's worldgen registries and produces a flat list of {@link OreEntry}.
 *
 * <p>Dimensions and biomes are resolved structurally rather than guessed from feature names:
 * every {@code LevelStem} exposes a chunk generator, which exposes a biome source, which lists
 * its possible biomes, each of which carries the placed features that generate inside it. That
 * chain is the only authoritative answer to "where does this ore actually spawn".</p>
 */
public final class WorldGenIndexer {

    private WorldGenIndexer() {
    }

    public static List<OreEntry> index(MinecraftServer server) {
        List<OreEntry> results = new ArrayList<>(indexFeatures(server));

        if (ModList.get().isLoaded("gtceu") && Config.INCLUDE_GREGTECH.get()) {
            try {
                results.addAll(GregTechCompat.extract(server));
            } catch (Throwable t) {
                // Loudly, not silently. A GT API change should show up in the log, not vanish.
                EMIOreGeneration.LOGGER.error("GregTech vein extraction failed; GT entries will be missing", t);
            }
        }

        return resolveDrops(server, results);
    }

    // ------------------------------------------------------------------
    // Loot table drops
    // ------------------------------------------------------------------

    /**
     * Attaches each entry's actual mining yield.
     *
     * <p>Players look things up by what lands in their inventory, not by the block in the wall:
     * Raw Iron, Coal, GregTech's crushed ores. Loot tables are the only source that knows this,
     * they live on the server, and they cover every mod without special-casing any of them.</p>
     */
    private static List<OreEntry> resolveDrops(MinecraftServer server, List<OreEntry> entries) {
        Map<String, List<String>> cache = new HashMap<>();
        List<OreEntry> out = new ArrayList<>(entries.size());
        for (OreEntry entry : entries) {
            out.add(entry.withDrops(
                    cache.computeIfAbsent(entry.blockId(), id -> dropsOf(server, id))));
        }
        return out;
    }

    private static List<String> dropsOf(MinecraftServer server, String blockId) {
        ResourceLocation location = ResourceLocation.tryParse(blockId);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            return List.of();
        }

        Block block = BuiltInRegistries.BLOCK.get(location);
        try {
            LootTable table = server.reloadableRegistries().getLootTable(block.getLootTable());
            LootParams params = new LootParams.Builder(server.overworld())
                    .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                    .withParameter(LootContextParams.TOOL, new ItemStack(Items.NETHERITE_PICKAXE))
                    .withParameter(LootContextParams.BLOCK_STATE, block.defaultBlockState())
                    .create(LootContextParamSets.BLOCK);

            // Sampled rather than statically analysed, because pools can be weighted or
            // conditional. A handful of rolls is enough to see every branch that matters.
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (int roll = 0; roll < 8; roll++) {
                for (ItemStack stack : table.getRandomItems(params)) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (itemId != null) {
                        ids.add(itemId.toString());
                    }
                }
            }
            return List.copyOf(ids);
        } catch (Exception e) {
            EMIOreGeneration.LOGGER.debug("No resolvable drops for {}", blockId, e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Vanilla / generic mod features
    // ------------------------------------------------------------------

    private static List<OreEntry> indexFeatures(MinecraftServer server) {
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        Registry<PlacedFeature> placedFeatures = server.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        // Keyed by dimension + feature + block so the same feature across many biomes collapses
        // into one entry carrying a biome list, rather than hundreds of near-duplicate rows.
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stemEntry : stems.entrySet()) {
            String dimensionId = stemEntry.getKey().location().toString();
            LevelStem stem = stemEntry.getValue();

            int worldMinY = stem.type().value().minY();
            int worldMaxY = worldMinY + stem.type().value().height();

            Set<Holder<Biome>> possibleBiomes = stem.generator().getBiomeSource().possibleBiomes();
            int biomeCount = possibleBiomes.size();

            for (Holder<Biome> biomeHolder : possibleBiomes) {
                String biomeId = biomeHolder.unwrapKey()
                        .map(k -> k.location().toString())
                        .orElse("unknown");

                for (HolderSet<PlacedFeature> step : biomeHolder.value().getGenerationSettings().features()) {
                    for (Holder<PlacedFeature> featureHolder : step) {
                        PlacedFeature placed = featureHolder.value();

                        ResourceLocation featureId = placedFeatures.getKey(placed);
                        String sourceId = featureId != null ? featureId.toString() : "inline";

                        Placement placement = readPlacement(placed, ops, worldMinY, worldMaxY);

                        // ConfiguredFeature#getFeatures flattens nested selectors, so ores hidden
                        // inside random_selector / random_boolean_selector are still found.
                        placed.feature().value().getFeatures().forEach(configured ->
                                collectOres(configured, aggregates, dimensionId, biomeId, biomeCount,
                                        sourceId, placement, worldMinY, worldMaxY));
                    }
                }
            }
        }

        List<OreEntry> out = new ArrayList<>(aggregates.size());
        for (Aggregate agg : aggregates.values()) {
            out.add(agg.toEntry());
        }
        return out;
    }

    private static void collectOres(ConfiguredFeature<?, ?> configured,
                                    Map<String, Aggregate> aggregates,
                                    String dimensionId,
                                    String biomeId,
                                    int dimensionBiomeCount,
                                    String sourceId,
                                    Placement placement,
                                    int worldMinY,
                                    int worldMaxY) {
        if (!(configured.config() instanceof OreConfiguration oreConfig) || oreConfig.targetStates.isEmpty()) {
            return;
        }

        // A single OreConfiguration usually lists stone + deepslate variants of the same ore.
        // Each distinct block gets its own entry so both show up when looked up in EMI.
        for (OreConfiguration.TargetBlockState target : oreConfig.targetStates) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(target.state.getBlock());
            if (blockId == null) {
                continue;
            }

            String key = dimensionId + '|' + sourceId + '|' + blockId;
            Aggregate agg = aggregates.computeIfAbsent(key, k -> new Aggregate(
                    blockId.toString(), sourceId, dimensionId,
                    placement.minY, placement.maxY, placement.meanY,
                    worldMinY, worldMaxY,
                    oreConfig.size, placement.spawnPermille,
                    dimensionBiomeCount));
            agg.biomes.add(biomeId);
        }
    }

    // ------------------------------------------------------------------
    // Placement parsing
    // ------------------------------------------------------------------

    /** Y band and spawn likelihood pulled out of a placed feature's placement modifiers. */
    record Placement(int minY, int maxY, int meanY, int spawnPermille) {
    }

    /**
     * Reads height range and frequency out of a {@link PlacedFeature} by round-tripping it
     * through its codec. This is deliberately not reflection: the JSON shape is part of the
     * datapack format and is far more stable across versions than private field names.
     */
    private static Placement readPlacement(PlacedFeature placed,
                                           RegistryOps<JsonElement> ops,
                                           int worldMinY,
                                           int worldMaxY) {
        int minY = worldMinY;
        int maxY = worldMaxY;
        Integer mode = null;
        int count = 1;
        int rarity = 1;

        try {
            JsonElement encoded = PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed).result().orElse(null);
            if (encoded != null && encoded.isJsonObject()) {
                JsonObject root = encoded.getAsJsonObject();
                if (root.has("placement")) {
                    for (JsonElement modifierElement : root.getAsJsonArray("placement")) {
                        if (!modifierElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject modifier = modifierElement.getAsJsonObject();
                        if (!modifier.has("type")) {
                            continue;
                        }

                        switch (modifier.get("type").getAsString()) {
                            case "minecraft:height_range" -> {
                                JsonObject height = modifier.getAsJsonObject("height");
                                minY = parseAnchor(height.get("min_inclusive"), worldMinY, worldMinY, worldMaxY);
                                maxY = parseAnchor(height.get("max_inclusive"), worldMaxY, worldMinY, worldMaxY);
                                // Triangle distributions peak in the middle; vanilla iron and
                                // lapis both rely on this, so the mean is worth distinguishing.
                                if (height.has("type")
                                        && "minecraft:trapezoid".equals(height.get("type").getAsString())) {
                                    mode = (minY + maxY) / 2;
                                }
                            }
                            case "minecraft:count" -> count = parseIntProvider(modifier.get("count"), 1);
                            case "minecraft:rarity_filter" -> {
                                if (modifier.has("chance")) {
                                    rarity = Math.max(1, modifier.get("chance").getAsInt());
                                }
                            }
                            default -> {
                                // Other modifiers (biome filter, in_square, surface relative)
                                // don't affect the Y band or the per-chunk frequency.
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            EMIOreGeneration.LOGGER.debug("Could not decode placement for a feature; using dimension bounds", e);
        }

        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
        }

        // "How likely is a given chunk to contain this at all", expressed in permille.
        int spawnPermille = (int) Math.min(1000L, Math.round(1000.0 * count / rarity));

        return new Placement(minY, maxY, mode != null ? mode : (minY + maxY) / 2, spawnPermille);
    }

    private static int parseAnchor(JsonElement anchor, int fallback, int worldMinY, int worldMaxY) {
        if (anchor == null) {
            return fallback;
        }
        if (anchor.isJsonPrimitive()) {
            return anchor.getAsInt();
        }
        if (anchor.isJsonObject()) {
            JsonObject obj = anchor.getAsJsonObject();
            if (obj.has("absolute")) {
                return obj.get("absolute").getAsInt();
            }
            if (obj.has("above_bottom")) {
                return worldMinY + obj.get("above_bottom").getAsInt();
            }
            if (obj.has("below_top")) {
                return worldMaxY - obj.get("below_top").getAsInt();
            }
        }
        return fallback;
    }

    /** Handles both a bare int and the {@code {"type":..., "value":...}} provider form. */
    private static int parseIntProvider(JsonElement element, int fallback) {
        if (element == null) {
            return fallback;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsInt();
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("value")) {
                return parseIntProvider(obj.get("value"), fallback);
            }
            if (obj.has("max_inclusive")) {
                return obj.get("max_inclusive").getAsInt();
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------

    /** Mutable accumulator that merges one feature's appearances across many biomes. */
    private static final class Aggregate {
        private final String blockId;
        private final String sourceId;
        private final String dimensionId;
        private final int minY;
        private final int maxY;
        private final int meanY;
        private final int worldMinY;
        private final int worldMaxY;
        private final int sizeBlocks;
        private final int spawnPermille;
        private final int dimensionBiomeCount;
        private final Set<String> biomes = new LinkedHashSet<>();

        private Aggregate(String blockId, String sourceId, String dimensionId,
                          int minY, int maxY, int meanY, int worldMinY, int worldMaxY,
                          int sizeBlocks, int spawnPermille, int dimensionBiomeCount) {
            this.blockId = blockId;
            this.sourceId = sourceId;
            this.dimensionId = dimensionId;
            this.minY = minY;
            this.maxY = maxY;
            this.meanY = meanY;
            this.worldMinY = worldMinY;
            this.worldMaxY = worldMaxY;
            this.sizeBlocks = sizeBlocks;
            this.spawnPermille = spawnPermille;
            this.dimensionBiomeCount = dimensionBiomeCount;
        }

        private OreEntry toEntry() {
            // Present in every biome of the dimension, so the biome cycler would just be noise.
            List<String> biomeList = biomes.size() >= dimensionBiomeCount
                    ? List.of()
                    : List.copyOf(biomes);

            return new OreEntry(
                    blockId, sourceId, SourceKind.FEATURE, dimensionId, biomeList,
                    minY, maxY, meanY, worldMinY, worldMaxY,
                    sizeBlocks, spawnPermille, 1000, "", "", List.of());
        }
    }
}
