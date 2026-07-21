package com.catx.emioregen.data;

public record OreEntryData(
        String blockId,
        String featureName,
        int minY,
        int maxY,
        int avgY,
        int variance,
        int count,
        String dimension       // E.g., "overworld", "nether", "end", "gregtech"
) {}