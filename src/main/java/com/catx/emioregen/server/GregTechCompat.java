package com.catx.emioregen.server;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntryData;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public class GregTechCompat {

    // Safely target the GT registry dynamically without hardcoded API imports
    private static final ResourceKey<Registry<Object>> GT_ORE_VEINS =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("gtceu", "ore_veins"));

    public static List<OreEntryData> extractGTOres(MinecraftServer server) {
        List<OreEntryData> results = new ArrayList<>();

        // Skip if GregTech isn't loaded
        if (!ModList.get().isLoaded("gtceu")) return results;

        try {
            server.registryAccess().registry(GT_ORE_VEINS).ifPresent(registry -> {
                for (var entry : registry.entrySet()) {
                    String featureName = entry.getKey().location().getPath();
                    Object veinDef = entry.getValue();

                    try {
                        // Use reflection to safely grab the math regardless of GT's internal package structure
                        Class<?> clazz = veinDef.getClass();
                        int minY = (int) clazz.getMethod("minY").invoke(veinDef);
                        int maxY = (int) clazz.getMethod("maxY").invoke(veinDef);
                        int weight = (int) clazz.getMethod("weight").invoke(veinDef);

                        int avgY = (minY + maxY) / 2;
                        int variance = Math.abs(maxY - minY) / 2;

                        results.add(new OreEntryData(
                                "minecraft:stone", // Fallback block icon
                                "GT Vein: " + featureName,
                                minY, maxY, avgY, variance, weight, "gregtech"
                        ));
                    } catch (Exception ignored) {
                        // Safely skip if the method names changed in a new snapshot
                    }
                }
            });
        } catch (Exception e) {
            EMIOreGeneration.LOGGER.error("Failed to dynamically parse GregTech veins", e);
        }
        return results;
    }
}