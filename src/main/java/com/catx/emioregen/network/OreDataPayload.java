package com.catx.emioregen.network;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ships the extracted ore index from server to client.
 *
 * <p>The payload is gzipped JSON. A modpack with GregTech can easily produce several thousand
 * entries, and NeoForge caps a custom payload at roughly 1 MiB; the data is highly repetitive
 * (the same dimension and biome ids over and over) so it compresses to a small fraction of its
 * raw size. {@link #estimateCompressedBytes()} exists so the sender can log the real figure.</p>
 */
public record OreDataPayload(List<OreEntry> oreEntries) implements CustomPacketPayload {

    public static final Type<OreDataPayload> TYPE = new Type<>(EMIOreGeneration.id("ore_data"));

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<OreEntry>>() {
    }.getType();

    public static final StreamCodec<ByteBuf, OreDataPayload> STREAM_CODEC = ByteBufCodecs.BYTE_ARRAY.map(
            bytes -> new OreDataPayload(decompress(bytes)),
            payload -> compress(payload.oreEntries())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public int estimateCompressedBytes() {
        return compress(oreEntries).length;
    }

    private static byte[] compress(List<OreEntry> entries) {
        byte[] json = GSON.toJson(entries, LIST_TYPE).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, json.length / 8));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compress ore data", e);
        }
        return out.toByteArray();
    }

    private static List<OreEntry> decompress(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            String json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            List<OreEntry> entries = GSON.fromJson(json, LIST_TYPE);
            return entries == null ? List.of() : entries;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decompress ore data", e);
        }
    }
}
