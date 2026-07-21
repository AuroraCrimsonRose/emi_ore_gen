package com.catx.emioregen.network;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntryData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Type;
import java.util.List;

public record OreDataPayload(List<OreEntryData> oreEntries) implements CustomPacketPayload {
    public static final Type<OreDataPayload> TYPE = new Type<>(EMIOreGeneration.id("ore_data"));
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<OreEntryData>>() {}.getType();

    // Use GSON to easily serialize the complex list over the network
    public static final StreamCodec<ByteBuf, OreDataPayload> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
            json -> new OreDataPayload(GSON.fromJson(json, LIST_TYPE)),
            payload -> GSON.toJson(payload.oreEntries())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}