package com.catx.emioregen.network;

import com.catx.emioregen.EMIOreGeneration;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record OreDataPayload(List<String> rawOreData) implements CustomPacketPayload {
    public static final Type<OreDataPayload> TYPE = new Type<>(EMIOreGeneration.id("ore_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OreDataPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.listOf().map(OreDataPayload::new, OreDataPayload::rawOreData);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}