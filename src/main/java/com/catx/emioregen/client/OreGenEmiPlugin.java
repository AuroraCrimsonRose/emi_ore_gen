package com.catx.emioregen.client;

import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntryData;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

@EmiEntrypoint
public class OreGenEmiPlugin implements EmiPlugin {

    public static final EmiRecipeCategory ORE_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("ore_gen"),
            EmiStack.of(Items.COAL_ORE)
    );

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(ORE_GEN_CATEGORY);
        registry.addWorkstation(ORE_GEN_CATEGORY, EmiStack.of(Items.COAL_ORE));
        registry.addWorkstation(ORE_GEN_CATEGORY, EmiStack.of(Items.DIAMOND_PICKAXE));

        int index = 0;
        for (OreEntryData data : ClientOreCache.getOres()) {
            registry.addRecipe(new OreGenEmiRecipe(EMIOreGeneration.id("/ore_gen_" + index++), data));
        }
    }
}