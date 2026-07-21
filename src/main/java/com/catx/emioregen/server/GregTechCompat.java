package com.catx.emioregen.server;

import com.catx.emioregen.Config;
import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SourceKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.bedrockfluid.BedrockFluidDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.bedrockore.BedrockOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.generator.IndicatorGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.generator.VeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.generator.indicators.SurfaceIndicatorGenerator;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts ore veins, bedrock ore deposits and bedrock fluid reservoirs from GregTech CEu.
 *
 * <p>Every GregTech symbol referenced here lives in this class alone. As long as nothing else
 * touches it, the JVM will not attempt to load it unless {@code gtceu} is present, so the mod
 * stays functional without GregTech installed.</p>
 *
 * <p>Verified against GregTech CEu Modern 8.0.0. The three registries used here
 * ({@code gtceu:ore_vein}, {@code gtceu:bedrock_ore}, {@code gtceu:bedrock_fluid}) are
 * datapack registries, so they live in {@code RegistryAccess} rather than as static fields.</p>
 */
final class GregTechCompat {

    private GregTechCompat() {
    }

    static List<OreEntry> extract(MinecraftServer server) {
        List<OreEntry> results = new ArrayList<>();
        results.addAll(extractVeins(server));
        results.addAll(extractBedrockOres(server));
        if (Config.INCLUDE_BEDROCK_FLUIDS.get()) {
            results.addAll(extractBedrockFluids(server));
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Ore veins
    // ------------------------------------------------------------------

    private static List<OreEntry> extractVeins(MinecraftServer server) {
        Registry<GTOreDefinition> registry =
                server.registryAccess().registry(GTRegistries.ORE_VEIN_REGISTRY).orElse(null);
        if (registry == null) {
            EMIOreGeneration.LOGGER.warn("GregTech is loaded but the {} registry is absent",
                    GTRegistries.ORE_VEIN_REGISTRY.location());
            return List.of();
        }

        Map<String, WorldBounds> bounds = dimensionBounds(server);

        // A vein's commonality is its weight relative to the other veins competing for the
        // same dimension, so totals have to be accumulated per dimension, not globally.
        Map<String, Long> totalWeightByDimension = new HashMap<>();
        for (GTOreDefinition vein : registry) {
            if (!vein.canGenerate()) {
                continue;
            }
            for (String dimension : dimensionsOf(vein, bounds.keySet())) {
                totalWeightByDimension.merge(dimension, (long) vein.weight(), Long::sum);
            }
        }

        List<OreEntry> results = new ArrayList<>();

        for (Map.Entry<ResourceKey<GTOreDefinition>, GTOreDefinition> entry : registry.entrySet()) {
            GTOreDefinition vein = entry.getValue();
            if (!vein.canGenerate()) {
                continue;
            }

            String veinId = entry.getKey().location().toString();
            String veinName = prettify(entry.getKey().location().getPath());

            VeinGenerator generator = vein.veinGenerator();
            if (generator == null) {
                continue;
            }

            List<VeinGenerator.VeinEntry> veinEntries;
            try {
                veinEntries = generator.getAllEntries();
            } catch (Exception e) {
                EMIOreGeneration.LOGGER.debug("Vein {} exposed no entries", veinId, e);
                continue;
            }
            if (veinEntries == null || veinEntries.isEmpty()) {
                continue;
            }

            long totalChance = 0;
            for (VeinGenerator.VeinEntry veinEntry : veinEntries) {
                totalChance += Math.max(0, veinEntry.chance());
            }
            if (totalChance <= 0) {
                totalChance = veinEntries.size();
            }

            YBand band = readHeightRange(vein.heightRange());
            int sizeBlocks = maxOf(vein.clusterSize());
            String indicatorBlockId = surfaceIndicatorOf(vein);
            List<String> biomeIds = biomeIdsOf(vein.biomes());

            for (String dimension : dimensionsOf(vein, bounds.keySet())) {
                WorldBounds wb = bounds.getOrDefault(dimension, WorldBounds.DEFAULT);
                long dimensionTotal = Math.max(1L, totalWeightByDimension.getOrDefault(dimension, 1L));
                int spawnPermille = (int) Math.min(1000L, Math.round(1000.0 * vein.weight() / dimensionTotal));

                int minY = band.min() != null ? band.min() : wb.minY();
                int maxY = band.max() != null ? band.max() : wb.maxY();

                // The rock is the cheapest prospecting signal in the game and generates on its
                // own terms, so it earns a row rather than just an icon beside the vein.
                if (!indicatorBlockId.isEmpty()) {
                    results.add(new OreEntry(
                            indicatorBlockId, veinId, SourceKind.GT_SURFACE_ROCK, dimension,
                            biomeIds, wb.maxY(), wb.maxY(), wb.maxY(), wb.minY(), wb.maxY(),
                            -1, spawnPermille, 1000, veinName, indicatorBlockId, List.of()));
                }

                for (VeinGenerator.VeinEntry veinEntry : veinEntries) {
                    String blockId = blockIdOf(veinEntry);
                    if (blockId == null) {
                        continue;
                    }
                    int sharePermille =
                            (int) Math.round(1000.0 * Math.max(0, veinEntry.chance()) / totalChance);

                    results.add(new OreEntry(
                            blockId, veinId, SourceKind.GT_VEIN, dimension, biomeIds,
                            minY, maxY, (minY + maxY) / 2, wb.minY(), wb.maxY(),
                            sizeBlocks, spawnPermille, sharePermille, veinName, indicatorBlockId, List.of()));
                }
            }
        }

        return results;
    }

    // ------------------------------------------------------------------
    // Bedrock ores and fluids
    // ------------------------------------------------------------------

    private static List<OreEntry> extractBedrockOres(MinecraftServer server) {
        Registry<BedrockOreDefinition> registry =
                server.registryAccess().registry(GTRegistries.BEDROCK_ORE_REGISTRY).orElse(null);
        if (registry == null) {
            return List.of();
        }

        Map<String, WorldBounds> bounds = dimensionBounds(server);
        Map<String, Long> totals = new HashMap<>();
        for (BedrockOreDefinition def : registry) {
            for (String dimension : dimensionKeys(def.dimensionFilter(), bounds.keySet())) {
                totals.merge(dimension, (long) def.weight(), Long::sum);
            }
        }

        List<OreEntry> results = new ArrayList<>();

        for (Map.Entry<ResourceKey<BedrockOreDefinition>, BedrockOreDefinition> entry : registry.entrySet()) {
            BedrockOreDefinition def = entry.getValue();
            String depositId = entry.getKey().location().toString();
            String depositName = prettify(entry.getKey().location().getPath());

            List<Material> materials = def.getAllMaterials();
            var chances = def.getAllChances();
            if (materials == null || materials.isEmpty()) {
                continue;
            }

            long totalChance = 0;
            for (int i = 0; i < chances.size(); i++) {
                totalChance += Math.max(0, chances.getInt(i));
            }
            if (totalChance <= 0) {
                totalChance = materials.size();
            }

            for (String dimension : dimensionKeys(def.dimensionFilter(), bounds.keySet())) {
                WorldBounds wb = bounds.getOrDefault(dimension, WorldBounds.DEFAULT);
                long dimensionTotal = Math.max(1L, totals.getOrDefault(dimension, 1L));
                int spawnPermille = (int) Math.min(1000L, Math.round(1000.0 * def.weight() / dimensionTotal));

                for (int i = 0; i < materials.size(); i++) {
                    String blockId = oreBlockIdOf(materials.get(i));
                    if (blockId == null) {
                        continue;
                    }
                    int chance = i < chances.size() ? Math.max(0, chances.getInt(i)) : 1;
                    int sharePermille = (int) Math.round(1000.0 * chance / totalChance);

                    // Bedrock deposits are not dug up; they sit below the world and are
                    // tapped by a miner, so the Y band is pinned to the bedrock layer.
                    results.add(new OreEntry(
                            blockId, depositId, SourceKind.GT_BEDROCK_ORE, dimension, List.of(),
                            wb.minY(), wb.minY(), wb.minY(), wb.minY(), wb.maxY(),
                            def.size(), spawnPermille, sharePermille, depositName, "", List.of()));
                }
            }
        }

        return results;
    }

    private static List<OreEntry> extractBedrockFluids(MinecraftServer server) {
        Registry<BedrockFluidDefinition> registry =
                server.registryAccess().registry(GTRegistries.BEDROCK_FLUID_REGISTRY).orElse(null);
        if (registry == null) {
            return List.of();
        }

        Map<String, WorldBounds> bounds = dimensionBounds(server);
        Map<String, Long> totals = new HashMap<>();
        for (BedrockFluidDefinition def : registry) {
            for (String dimension : dimensionKeys(def.getDimensionFilter(), bounds.keySet())) {
                totals.merge(dimension, (long) def.getWeight(), Long::sum);
            }
        }

        List<OreEntry> results = new ArrayList<>();

        for (Map.Entry<ResourceKey<BedrockFluidDefinition>, BedrockFluidDefinition> entry : registry.entrySet()) {
            BedrockFluidDefinition def = entry.getValue();
            Fluid fluid = def.getStoredFluid();
            if (fluid == null) {
                continue;
            }
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            if (fluidId == null) {
                continue;
            }

            String reservoirId = entry.getKey().location().toString();
            String reservoirName = prettify(entry.getKey().location().getPath());

            for (String dimension : dimensionKeys(def.getDimensionFilter(), bounds.keySet())) {
                WorldBounds wb = bounds.getOrDefault(dimension, WorldBounds.DEFAULT);
                long dimensionTotal = Math.max(1L, totals.getOrDefault(dimension, 1L));
                int spawnPermille = (int) Math.min(1000L, Math.round(1000.0 * def.getWeight() / dimensionTotal));

                results.add(new OreEntry(
                        fluidId.toString(), reservoirId, SourceKind.GT_BEDROCK_FLUID, dimension, List.of(),
                        wb.minY(), wb.minY(), wb.minY(), wb.minY(), wb.maxY(),
                        -1, spawnPermille, 1000, reservoirName, "", List.of()));
            }
        }

        return results;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Y band read out of a height range placement; null bounds mean "unconstrained". */
    private record YBand(Integer min, Integer max) {
    }

    /**
     * GregTech stores its vein height as a vanilla {@link HeightRangePlacement}, whose
     * {@code HeightProvider} has no public accessors. Encoding through the placement codec
     * gets at the values without reflection or an access transformer.
     */
    private static YBand readHeightRange(HeightRangePlacement placement) {
        if (placement == null) {
            return new YBand(null, null);
        }
        try {
            JsonElement encoded = PlacementModifier.CODEC
                    .encodeStart(JsonOps.INSTANCE, placement).result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) {
                return new YBand(null, null);
            }
            JsonObject height = encoded.getAsJsonObject().getAsJsonObject("height");
            if (height == null) {
                return new YBand(null, null);
            }
            return new YBand(
                    anchor(height.get("min_inclusive")),
                    anchor(height.get("max_inclusive")));
        } catch (Exception e) {
            EMIOreGeneration.LOGGER.debug("Could not decode a GregTech height range", e);
            return new YBand(null, null);
        }
    }

    private static Integer anchor(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsInt();
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("absolute")) {
                return obj.get("absolute").getAsInt();
            }
            if (obj.has("above_bottom")) {
                return -64 + obj.get("above_bottom").getAsInt();
            }
            if (obj.has("below_top")) {
                return 320 - obj.get("below_top").getAsInt();
            }
        }
        return null;
    }

    private static int maxOf(net.minecraft.util.valueproviders.IntProvider provider) {
        return provider == null ? -1 : provider.getMaxValue();
    }

    /** A vein entry is either a concrete block state or a GregTech material. */
    private static String blockIdOf(VeinGenerator.VeinEntry entry) {
        BlockState state = entry.mapToBlockState();
        if (state != null && !state.isAir()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (id != null) {
                return id.toString();
            }
        }
        Material material = entry.mapToMaterial();
        return material == null ? null : oreBlockIdOf(material);
    }

    private static String oreBlockIdOf(Material material) {
        Block block = ChemicalHelper.getBlock(TagPrefix.ore, material);
        if (block == null) {
            block = ChemicalHelper.getBlock(TagPrefix.rawOreBlock, material);
        }
        if (block == null) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : id.toString();
    }

    /** The surface rock / dust pile that hints at a vein below, if the vein places one. */
    private static String surfaceIndicatorOf(GTOreDefinition vein) {
        if (!Config.SHOW_SURFACE_INDICATORS.get()) {
            return "";
        }
        List<IndicatorGenerator> generators = vein.indicatorGenerators();
        if (generators == null) {
            return "";
        }
        for (IndicatorGenerator generator : generators) {
            if (!(generator instanceof SurfaceIndicatorGenerator surface)) {
                continue;
            }
            var either = surface.block();
            if (either == null) {
                continue;
            }
            String id = either.map(
                    state -> {
                        ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        return loc == null ? null : loc.toString();
                    },
                    GregTechCompat::oreBlockIdOf);
            if (id != null) {
                return id;
            }
        }
        return "";
    }

    private static List<String> biomeIdsOf(HolderSet<Biome> biomes) {
        if (biomes == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Holder<Biome> holder : biomes) {
            holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()));
        }
        return ids;
    }

    private static List<String> dimensionsOf(GTOreDefinition vein, java.util.Set<String> known) {
        var filter = vein.dimensionFilter();
        if (filter == null || filter.isEmpty()) {
            // No explicit filter, so fall back to whatever layer the vein belongs to.
            if (vein.layer() != null && vein.layer().getLevels() != null && !vein.layer().getLevels().isEmpty()) {
                return dimensionKeys(vein.layer().getLevels(), known);
            }
            return List.of(Level.OVERWORLD.location().toString());
        }
        return dimensionKeys(filter, known);
    }

    private static List<String> dimensionKeys(java.util.Set<ResourceKey<Level>> keys, java.util.Set<String> known) {
        if (keys == null || keys.isEmpty()) {
            return List.of(Level.OVERWORLD.location().toString());
        }
        List<String> out = new ArrayList<>(keys.size());
        for (ResourceKey<Level> key : keys) {
            String id = key.location().toString();
            // Drop dimensions that this world does not actually have loaded.
            if (known.isEmpty() || known.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private record WorldBounds(int minY, int maxY) {
        private static final WorldBounds DEFAULT = new WorldBounds(-64, 320);
    }

    private static Map<String, WorldBounds> dimensionBounds(MinecraftServer server) {
        Map<String, WorldBounds> bounds = new HashMap<>();
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : stems.entrySet()) {
            var type = entry.getValue().type().value();
            bounds.put(entry.getKey().location().toString(),
                    new WorldBounds(type.minY(), type.minY() + type.height()));
        }
        return bounds;
    }

    private static String prettify(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder(path.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
