package com.catx.emioregen.server;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SourceKind;
import flaxbeard.immersivepetroleum.api.reservoir.ReservoirType;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts Immersive Petroleum's underground fluid reservoirs.
 *
 * <p>These are not worldgen features and are not in any worldgen registry: IP defines them as
 * recipes and keeps them in {@code ReservoirType.map}. Where they apply is expressed as
 * whitelist or blacklist filters over dimensions and biomes rather than a plain list, so the
 * only honest way to know where one occurs is to ask it about every dimension and biome the
 * world actually has.</p>
 *
 * <p>Every Immersive Petroleum symbol referenced here lives in this class alone, so the JVM
 * will not try to load it unless {@code immersivepetroleum} is present.</p>
 */
final class ImmersivePetroleumCompat {

    private ImmersivePetroleumCompat() {
    }

    static List<OreEntry> extract(MinecraftServer server) {
        Map<ResourceLocation, RecipeHolder<ReservoirType>> registered = ReservoirType.map;
        if (registered == null || registered.isEmpty()) {
            return List.of();
        }

        List<OreEntry> results = new ArrayList<>();
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stemEntry : stems.entrySet()) {
            ResourceLocation dimensionLocation = stemEntry.getKey().location();
            ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionLocation);

            LevelStem stem = stemEntry.getValue();
            int worldMinY = stem.type().value().minY();
            int worldMaxY = worldMinY + stem.type().value().height();

            Set<Holder<Biome>> possibleBiomes = stem.generator().getBiomeSource().possibleBiomes();

            // Which reservoirs apply here, and in which of this dimension's biomes.
            Map<ReservoirType, List<String>> applicable = new LinkedHashMap<>();
            for (RecipeHolder<ReservoirType> holder : registered.values()) {
                ReservoirType reservoir = holder.value();
                try {
                    if (!reservoir.getDimensions().isValid(levelKey)) {
                        continue;
                    }

                    List<String> biomeIds = new ArrayList<>();
                    for (Holder<Biome> biome : possibleBiomes) {
                        if (reservoir.getBiomes().isValid(biome)) {
                            biome.unwrapKey().ifPresent(key -> biomeIds.add(key.location().toString()));
                        }
                    }
                    if (biomeIds.isEmpty()) {
                        continue;
                    }

                    // Present throughout, so the biome cycler would only be noise.
                    applicable.put(reservoir,
                            biomeIds.size() >= possibleBiomes.size() ? List.of() : biomeIds);
                } catch (Exception e) {
                    EMIOreGeneration.LOGGER.debug("Could not evaluate reservoir {} for {}",
                            reservoir.name, dimensionLocation, e);
                }
            }

            if (applicable.isEmpty()) {
                continue;
            }

            long totalWeight = applicable.keySet().stream().mapToLong(r -> r.weight).sum();
            if (totalWeight <= 0) {
                totalWeight = 1;
            }

            for (Map.Entry<ReservoirType, List<String>> entry : applicable.entrySet()) {
                ReservoirType reservoir = entry.getKey();

                Fluid fluid = reservoir.getFluid();
                if (fluid == null) {
                    continue;
                }
                ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
                if (fluidId == null) {
                    continue;
                }

                int spawnPermille =
                        (int) Math.min(1000L, Math.round(1000.0 * reservoir.weight / totalWeight));

                // A reservoir is a region you drill into rather than a band you dig down to, so
                // it carries no meaningful Y and the display says "Reservoir" instead.
                results.add(new OreEntry(
                        fluidId.toString(),
                        "immersivepetroleum:" + reservoir.name,
                        SourceKind.IP_RESERVOIR,
                        dimensionLocation.toString(),
                        entry.getValue(),
                        worldMinY, worldMinY, worldMinY, worldMinY, worldMaxY,
                        -1, spawnPermille, 1000, "", "", List.of()));
            }
        }

        return results;
    }
}
