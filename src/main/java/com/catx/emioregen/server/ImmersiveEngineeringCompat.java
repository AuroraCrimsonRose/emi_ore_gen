package com.catx.emioregen.server;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SourceKind;
import blusunrize.immersiveengineering.api.excavator.MineralMix;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Extracts Immersive Engineering's excavator mineral deposits.
 *
 * <p>Like Immersive Petroleum's reservoirs these are recipes rather than worldgen features, and
 * where they apply is a biome tag predicate rather than a list, so each mix has to be asked
 * about every biome the world actually has.</p>
 *
 * <p>A mix names its ores through a weighted table, so rather than unpicking the weights the
 * table is sampled and the distinct results collected. That handles tag-based outputs without
 * this class needing to know anything about how they resolve.</p>
 *
 * <p>Every Immersive Engineering symbol referenced here lives in this class alone, so the JVM
 * will not try to load it unless {@code immersiveengineering} is present.</p>
 */
final class ImmersiveEngineeringCompat {

    /** Rolls of a mix's ore table. Enough to see every entry that carries real weight. */
    private static final int SAMPLES = 64;

    private ImmersiveEngineeringCompat() {
    }

    static List<OreEntry> extract(MinecraftServer server) {
        ServerLevel overworld = server.overworld();

        Collection<ResourceLocation> names = MineralMix.RECIPES.getRecipeNames(overworld);
        if (names == null || names.isEmpty()) {
            return List.of();
        }

        Map<ResourceLocation, MineralMix> mixes = new LinkedHashMap<>();
        for (ResourceLocation name : names) {
            MineralMix mix = MineralMix.RECIPES.getById(overworld, name);
            if (mix != null) {
                mixes.put(name, mix);
            }
        }
        if (mixes.isEmpty()) {
            return List.of();
        }

        List<OreEntry> results = new ArrayList<>();
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stemEntry : stems.entrySet()) {
            String dimensionId = stemEntry.getKey().location().toString();
            LevelStem stem = stemEntry.getValue();

            int worldMinY = stem.type().value().minY();
            int worldMaxY = worldMinY + stem.type().value().height();

            Set<Holder<Biome>> possibleBiomes = stem.generator().getBiomeSource().possibleBiomes();

            Map<ResourceLocation, List<String>> applicable = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, MineralMix> entry : mixes.entrySet()) {
                try {
                    List<String> biomeIds = new ArrayList<>();
                    for (Holder<Biome> biome : possibleBiomes) {
                        if (entry.getValue().validBiome(biome)) {
                            biome.unwrapKey().ifPresent(key -> biomeIds.add(key.location().toString()));
                        }
                    }
                    if (biomeIds.isEmpty()) {
                        continue;
                    }
                    // Present throughout, so the biome cycler would only be noise.
                    applicable.put(entry.getKey(),
                            biomeIds.size() >= possibleBiomes.size() ? List.of() : biomeIds);
                } catch (Exception e) {
                    EMIOreGeneration.LOGGER.debug("Could not evaluate mineral mix {} for {}",
                            entry.getKey(), dimensionId, e);
                }
            }

            if (applicable.isEmpty()) {
                continue;
            }

            long totalWeight = applicable.keySet().stream()
                    .mapToLong(name -> mixes.get(name).weight)
                    .sum();
            if (totalWeight <= 0) {
                totalWeight = 1;
            }

            for (Map.Entry<ResourceLocation, List<String>> entry : applicable.entrySet()) {
                MineralMix mix = mixes.get(entry.getKey());
                String depositName = MineralMix.getPlainName(entry.getKey());

                int spawnPermille =
                        (int) Math.min(1000L, Math.round(1000.0 * mix.weight / totalWeight));

                List<String> ores = sampleOres(mix);
                for (String oreId : ores) {
                    // A deposit is drilled from the surface over a whole region rather than dug
                    // down to, so it carries no meaningful Y band.
                    results.add(new OreEntry(
                            oreId,
                            entry.getKey().toString(),
                            SourceKind.IE_MINERAL,
                            dimensionId,
                            entry.getValue(),
                            worldMinY, worldMinY, worldMinY, worldMinY, worldMaxY,
                            -1, spawnPermille,
                            Math.max(1, 1000 / Math.max(1, ores.size())),
                            depositName, "", List.of()));
                }
            }
        }

        return results;
    }

    /**
     * The distinct ores a mix can yield.
     *
     * <p>Sampled rather than read off the output list, because entries can be tag-backed and
     * conditional. Sampling asks the mix the same question the excavator does.</p>
     */
    private static List<String> sampleOres(MineralMix mix) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Random random = new Random(0);
        for (int roll = 0; roll < SAMPLES; roll++) {
            try {
                ItemStack stack = mix.getRandomOre(random);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) {
                    ids.add(id.toString());
                }
            } catch (Exception e) {
                break;
            }
        }
        return List.copyOf(ids);
    }
}
