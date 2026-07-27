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

        if (ModList.get().isLoaded("immersiveengineering") && Config.INCLUDE_IMMERSIVE_ENGINEERING.get()) {
            try {
                results.addAll(ImmersiveEngineeringCompat.extract(server));
            } catch (Throwable t) {
                EMIOreGeneration.LOGGER.error(
                        "Immersive Engineering mineral extraction failed; those entries will be missing", t);
            }
        }

        if (ModList.get().isLoaded("immersivepetroleum") && Config.INCLUDE_IMMERSIVE_PETROLEUM.get()) {
            try {
                results.addAll(ImmersivePetroleumCompat.extract(server));
            } catch (Throwable t) {
                EMIOreGeneration.LOGGER.error(
                        "Immersive Petroleum reservoir extraction failed; those entries will be missing", t);
            }
        }

        if (ModList.get().isLoaded("gtceu") && Config.INCLUDE_GREGTECH.get()) {
            try {
                results.addAll(GregTechCompat.extract(server));
            } catch (Throwable t) {
                // Loudly, not silently. A GT API change should show up in the log, not vanish.
                EMIOreGeneration.LOGGER.error("GregTech vein extraction failed; GT entries will be missing", t);
            }
        }

        warnAboutUnreadableMods();

        return resolveDrops(server, results);
    }

    /**
     * Names mods whose worldgen this cannot see, so a wrong-looking page has an explanation.
     *
     * <p>Large Ore Deposits is the awkward case: its own deposits live in its config files with
     * nothing in any registry to read, and it mixes into {@code ConfiguredFeature} to rewrite or
     * disable vanilla ore generation. The rewriting is fine, because this mod reads the live
     * registries and therefore sees the result rather than the original. The deposits are simply
     * invisible.</p>
     */
    private static void warnAboutUnreadableMods() {
        if (ModList.get().isLoaded("adlods")) {
            EMIOreGeneration.LOGGER.info(
                    "Large Ore Deposits is present. Its own deposits are configured outside any "
                            + "registry and cannot be indexed; vanilla ores it modifies are read "
                            + "after modification and should be accurate.");
        }
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
        Registry<ConfiguredFeature<?, ?>> configuredFeatures =
                server.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);

        // Keyed by dimension + feature + block so the same feature across many biomes collapses
        // into one entry carrying a biome list, rather than hundreds of near-duplicate rows.
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stemEntry : stems.entrySet()) {
            String dimensionId = stemEntry.getKey().location().toString();

            // A single broken dimension should cost that dimension, not the whole index.
            try {
                indexDimension(stemEntry.getValue(), dimensionId, placedFeatures,
                        configuredFeatures, ops, aggregates);
            } catch (Throwable t) {
                EMIOreGeneration.LOGGER.warn("Skipping dimension {}: its worldgen could not be read",
                        dimensionId, t);
            }
        }

        List<OreEntry> out = new ArrayList<>(aggregates.size());
        for (Aggregate agg : aggregates.values()) {
            out.add(agg.toEntry());
        }
        return out;
    }

    private static void indexDimension(LevelStem stem, String dimensionId,
                                       Registry<PlacedFeature> placedFeatures,
                                       Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                                       RegistryOps<JsonElement> ops,
                                       Map<String, Aggregate> aggregates) {
        int worldMinY = stem.type().value().minY();
        int worldMaxY = worldMinY + stem.type().value().height();

        Set<Holder<Biome>> possibleBiomes = stem.generator().getBiomeSource().possibleBiomes();
        int biomeCount = possibleBiomes.size();

        for (Holder<Biome> biomeHolder : possibleBiomes) {
            String biomeId = biomeHolder.unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown");

            try {
                for (HolderSet<PlacedFeature> step : biomeHolder.value().getGenerationSettings().features()) {
                    for (Holder<PlacedFeature> featureHolder : step) {
                        indexFeature(featureHolder, placedFeatures, configuredFeatures, ops,
                                aggregates, dimensionId, biomeId, biomeCount, worldMinY, worldMaxY);
                    }
                }
            } catch (Throwable t) {
                // Unbound holders and malformed generation settings both land here.
                EMIOreGeneration.LOGGER.debug("Skipping biome {} in {}", biomeId, dimensionId, t);
            }
        }
    }

    private static void indexFeature(Holder<PlacedFeature> featureHolder,
                                     Registry<PlacedFeature> placedFeatures,
                                     Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                                     RegistryOps<JsonElement> ops,
                                     Map<String, Aggregate> aggregates,
                                     String dimensionId, String biomeId, int biomeCount,
                                     int worldMinY, int worldMaxY) {
        try {
            PlacedFeature placed = featureHolder.value();

            ResourceLocation featureId = placedFeatures.getKey(placed);
            String sourceId = featureId != null ? featureId.toString() : "inline";

            Placement placement = readPlacement(placed, ops, worldMinY, worldMaxY);

            // ConfiguredFeature#getFeatures flattens nested selectors, so ores hidden inside
            // random_selector / random_boolean_selector are still found. A mod with a feature
            // that references itself would recurse forever here, hence catching Throwable
            // rather than Exception: that arrives as a StackOverflowError.
            placed.feature().value().getFeatures().forEach(configured ->
                    collectOres(configured, configuredFeatures, ops, aggregates, dimensionId,
                            biomeId, biomeCount, sourceId, placement, worldMinY, worldMaxY));
        } catch (Throwable t) {
            EMIOreGeneration.LOGGER.debug("Skipping an unreadable feature in {} / {}",
                    dimensionId, biomeId, t);
        }
    }

    private static void collectOres(ConfiguredFeature<?, ?> configured,
                                    Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                                    RegistryOps<JsonElement> ops,
                                    Map<String, Aggregate> aggregates,
                                    String dimensionId,
                                    String biomeId,
                                    int dimensionBiomeCount,
                                    String sourceId,
                                    Placement placement,
                                    int worldMinY,
                                    int worldMaxY) {
        if (!(configured.config() instanceof OreConfiguration oreConfig) || oreConfig.targetStates.isEmpty()) {
            collectForeignOres(configured, configuredFeatures, ops, aggregates, dimensionId,
                    biomeId, dimensionBiomeCount, sourceId, placement, worldMinY, worldMaxY);
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

    /**
     * Ores hiding inside a mod's own feature type.
     *
     * <p>Plenty of mods define their own feature rather than using {@code minecraft:ore}, and
     * their config is usually a record rather than a subclass of {@link OreConfiguration}, so an
     * {@code instanceof} check misses them entirely. Mekanism, Immersive Engineering and Create
     * all do this, and between them that is most of a tech pack's ores.</p>
     *
     * <p>What they do share is the serialised shape: a {@code targets} list of
     * {@code {"state": {"Name": ...}}}, because they are all wrapping the same vanilla idea. So
     * rather than writing an extractor per mod, the feature is encoded through its own codec and
     * the result read for that shape. A mod released tomorrow gets picked up for free.</p>
     *
     * <p>The catch is that trees and decoration serialise block states too, so a match only
     * counts if the block is tagged as an ore or is named like one.</p>
     */
    private static void collectForeignOres(ConfiguredFeature<?, ?> configured,
                                           Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                                           RegistryOps<JsonElement> ops,
                                           Map<String, Aggregate> aggregates,
                                           String dimensionId, String biomeId,
                                           int dimensionBiomeCount, String sourceId,
                                           Placement placement, int worldMinY, int worldMaxY) {
        JsonElement encoded;
        try {
            encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, configured).result().orElse(null);
        } catch (Exception e) {
            return;
        }
        if (encoded == null) {
            return;
        }

        Set<String> blockIds = new LinkedHashSet<>();
        scanForTargets(encoded, blockIds, configuredFeatures, ops, new LinkedHashSet<>(), 0);

        if (blockIds.isEmpty()) {
            // Nothing serialised at all. A few features decide their contents in Java, so fall
            // back to the table, which still gets real rarity and depth from the placement.
            List<String> known = OpaqueFeatures.yieldsOf(sourceId);
            for (String resourceId : known) {
                String key = dimensionId + '|' + sourceId + '|' + resourceId;
                Aggregate agg = aggregates.computeIfAbsent(key, k -> new Aggregate(
                        resourceId, sourceId, SourceKind.FLUID_DEPOSIT, dimensionId,
                        placement.minY, placement.maxY, placement.meanY,
                        worldMinY, worldMaxY,
                        -1, placement.spawnPermille, dimensionBiomeCount));
                agg.biomes.add(biomeId);
            }
            return;
        }

        int size = scanForSize(encoded, 0);

        for (String blockId : blockIds) {
            ResourceLocation location = ResourceLocation.tryParse(blockId);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
                continue;
            }
            if (!looksLikeOre(location)) {
                continue;
            }

            String key = dimensionId + '|' + sourceId + '|' + blockId;
            Aggregate agg = aggregates.computeIfAbsent(key, k -> new Aggregate(
                    blockId, sourceId, dimensionId,
                    placement.minY, placement.maxY, placement.meanY,
                    worldMinY, worldMaxY,
                    size, placement.spawnPermille, dimensionBiomeCount));
            agg.biomes.add(biomeId);
        }
    }

    /**
     * Collects every {@code targets[].state.Name} anywhere in the tree, following references.
     *
     * <p>Wrapper features are the reason for the reference following. Libraries like
     * Lithostitched let a pack say "one of these features, weighted", and the nested entries are
     * serialised as ids rather than inline. None of those wrappers override
     * {@code Feature#getFeatures}, so vanilla's flattening cannot see through them either, and an
     * ore behind one would simply not exist as far as this mod is concerned. Resolving the id
     * against the registry and carrying on handles every wrapper the same way, including ones
     * that do not exist yet.</p>
     *
     * <p>The visited set is not an optimisation: a pack can define two features that reference
     * each other, and without it that is an infinite loop during world load.</p>
     */
    private static void scanForTargets(JsonElement element, Set<String> out,
                                       Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                                       RegistryOps<JsonElement> ops,
                                       Set<String> visited, int depth) {
        if (depth > 12 || element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child ->
                    scanForTargets(child, out, configuredFeatures, ops, visited, depth + 1));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject obj = element.getAsJsonObject();

        if (obj.has("state") && obj.get("state").isJsonObject()) {
            JsonObject state = obj.getAsJsonObject("state");
            if (state.has("Name") && state.get("Name").isJsonPrimitive()) {
                out.add(state.get("Name").getAsString());
            }
        }

        if (obj.has("feature") && obj.get("feature").isJsonPrimitive()) {
            follow(obj.get("feature").getAsString(), out, configuredFeatures, ops, visited, depth);
        }

        obj.entrySet().forEach(entry ->
                scanForTargets(entry.getValue(), out, configuredFeatures, ops, visited, depth + 1));
    }

    private static void follow(String featureId, Set<String> out,
                               Registry<ConfiguredFeature<?, ?>> configuredFeatures,
                               RegistryOps<JsonElement> ops, Set<String> visited, int depth) {
        if (!visited.add(featureId)) {
            return;
        }
        ResourceLocation location = ResourceLocation.tryParse(featureId);
        if (location == null) {
            return;
        }
        ConfiguredFeature<?, ?> nested = configuredFeatures.get(location);
        if (nested == null) {
            return;
        }
        try {
            JsonElement encoded = ConfiguredFeature.DIRECT_CODEC
                    .encodeStart(ops, nested).result().orElse(null);
            if (encoded != null) {
                scanForTargets(encoded, out, configuredFeatures, ops, visited, depth + 1);
            }
        } catch (Exception ignored) {
            // A feature that will not serialise simply contributes nothing.
        }
    }

    private static int scanForSize(JsonElement element, int depth) {
        if (depth > 12 || element == null || !element.isJsonObject()) {
            return -1;
        }
        JsonObject obj = element.getAsJsonObject();
        for (String field : new String[] {"size", "max_size", "vein_size"}) {
            if (obj.has(field) && obj.get(field).isJsonPrimitive()) {
                try {
                    return obj.get(field).getAsInt();
                } catch (Exception ignored) {
                    // Some mods make these value providers; fall through to the nested search.
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            int found = scanForSize(entry.getValue(), depth + 1);
            if (found >= 0) {
                return found;
            }
        }
        return -1;
    }

    /**
     * Whether a block is plausibly an ore rather than scenery.
     *
     * <p>Tags first, because that is what the block itself claims to be. The name check is a
     * fallback for ores whose mod never tagged them, which is common enough to be worth it.</p>
     */
    private static boolean looksLikeOre(ResourceLocation location) {
        try {
            boolean tagged = BuiltInRegistries.BLOCK.getHolder(
                            ResourceKey.create(Registries.BLOCK, location))
                    .map(holder -> holder.tags().anyMatch(tag ->
                            tag.location().getPath().startsWith("ores/")
                                    || tag.location().getPath().equals("ores")))
                    .orElse(false);
            if (tagged) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to the name check.
        }
        String path = location.getPath();
        return path.endsWith("_ore") || path.startsWith("ore_") || path.contains("_ore_");
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
        private final SourceKind kind;
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
            this(blockId, sourceId, SourceKind.FEATURE, dimensionId, minY, maxY, meanY,
                    worldMinY, worldMaxY, sizeBlocks, spawnPermille, dimensionBiomeCount);
        }

        private Aggregate(String blockId, String sourceId, SourceKind kind, String dimensionId,
                          int minY, int maxY, int meanY, int worldMinY, int worldMaxY,
                          int sizeBlocks, int spawnPermille, int dimensionBiomeCount) {
            this.blockId = blockId;
            this.sourceId = sourceId;
            this.kind = kind;
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
            // Present in effectively every biome of the dimension, so the biome cycler would
            // just be noise. The threshold matters: in a pack with hundreds of biomes, shipping
            // "599 of 600" as a literal list costs a great deal and tells a player nothing.
            int threshold = (int) Math.ceil(
                    dimensionBiomeCount * (Config.BIOME_COVERAGE_THRESHOLD.get() / 100.0));
            List<String> biomeList = biomes.size() >= Math.max(1, threshold)
                    ? List.of()
                    : List.copyOf(biomes);

            return new OreEntry(
                    blockId, sourceId, kind, dimensionId, biomeList,
                    minY, maxY, meanY, worldMinY, worldMaxY,
                    sizeBlocks, spawnPermille, 1000, "", "", List.of());
        }
    }
}
