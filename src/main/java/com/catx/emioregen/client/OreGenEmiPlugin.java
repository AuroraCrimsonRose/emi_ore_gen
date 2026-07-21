package com.catx.emioregen.client;

import com.catx.emioregen.EMIOreGeneration;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.Items;

@EmiEntrypoint
public class OreGenEmiPlugin implements EmiPlugin {
    public static final EmiRecipeCategory ORE_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("ore_gen"),
            EmiStack.of(Items.RAW_IRON)
    );

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(ORE_GEN_CATEGORY);

        // Loop through cached ore data received from server payload and add EMI recipes here
        EMIOreGeneration.LOGGER.info("Registered EMI Ore Generation category!");
    }
}