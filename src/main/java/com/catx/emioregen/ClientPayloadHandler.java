package com.catx.emioregen;

import com.catx.emioregen.client.ClientOreCache;
import com.catx.emioregen.network.OreDataPayload;
import dev.emi.emi.runtime.EmiReloadManager;

/**
 * Client-only landing point for the ore data payload.
 *
 * <p>Kept in its own class so that the EMI and client-side references never appear in the
 * bytecode of a class the dedicated server loads.</p>
 */
final class ClientPayloadHandler {

    private static int lastPayloadHash;

    private ClientPayloadHandler() {
    }

    static void accept(OreDataPayload payload) {
        var entries = payload.oreEntries();

        // Rejoining the same world resends identical data, and rebaking EMI costs the better
        // part of a minute in a pack this size, so only reload when something actually changed.
        int hash = entries.hashCode();
        if (hash == lastPayloadHash) {
            EMIOreGeneration.LOGGER.debug("Ore index unchanged; skipping EMI reload");
            return;
        }
        lastPayloadHash = hash;

        ClientOreCache.update(entries);
        EMIOreGeneration.LOGGER.info("Cached {} ore occurrences for EMI", entries.size());
        EmiReloadManager.reload();
    }
}
