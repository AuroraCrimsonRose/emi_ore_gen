package com.catx.emioregen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entrypoint. Never loaded on a dedicated server. */
@Mod(value = EMIOreGeneration.MODID, dist = Dist.CLIENT)
public class EMIOreGenerationClient {

    public EMIOreGenerationClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
