package com.catx.emioregen.client;

import com.catx.emioregen.data.OreEntryData;
import java.util.ArrayList;
import java.util.List;

public class ClientOreCache {
    private static final List<OreEntryData> ORES = new ArrayList<>();

    public static void update(List<OreEntryData> data) {
        ORES.clear();
        ORES.addAll(data);
    }

    public static List<OreEntryData> getOres() {
        return ORES;
    }
}