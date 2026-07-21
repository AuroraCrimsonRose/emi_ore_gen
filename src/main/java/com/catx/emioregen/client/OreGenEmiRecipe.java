package com.catx.emioregen.client;

import com.catx.emioregen.data.OreEntryData;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public class OreGenEmiRecipe implements EmiRecipe {
    private final ResourceLocation id;
    private final OreEntryData data;
    private final EmiStack oreStack;
    private final EmiStack dimensionStack;

    public OreGenEmiRecipe(ResourceLocation id, OreEntryData data) {
        this.id = id;
        this.data = data;

        ResourceLocation blockLoc = ResourceLocation.tryParse(data.blockId());
        if (blockLoc != null && BuiltInRegistries.ITEM.containsKey(blockLoc)) {
            this.oreStack = EmiStack.of(new ItemStack(BuiltInRegistries.ITEM.get(blockLoc)));
        } else {
            this.oreStack = EmiStack.EMPTY;
        }

        // Smarter icon mapping
        this.dimensionStack = switch (data.dimension().toLowerCase()) {
            case "nether" -> EmiStack.of(Items.NETHERRACK);
            case "the end" -> EmiStack.of(Items.END_STONE);
            case "gregtech" -> EmiStack.of(Items.RAW_IRON);
            case "moon" -> EmiStack.of(Items.END_STONE); // Fallback visual for Moon
            case "mars" -> EmiStack.of(Items.RED_SAND); // Fallback visual for Mars
            default -> EmiStack.of(Items.GRASS_BLOCK); // Overworld
        };
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return OreGenEmiPlugin.ORE_GEN_CATEGORY;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return List.of(dimensionStack);
    }

    @Override
    public List<EmiStack> getOutputs() {
        return oreStack.isEmpty() ? List.of() : List.of(oreStack);
    }

    @Override
    public int getDisplayWidth() {
        return 160;
    }

    @Override
    public int getDisplayHeight() {
        return 60; // Increased height to fit all the text comfortably
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        // Main Ore Output
        widgets.addSlot(oreStack, 6, 6).recipeContext(this);

        // Dimension Filter Input Slot with hover tooltip
        widgets.addSlot(dimensionStack, 6, 32).drawBack(false).recipeContext(this)
                .appendTooltip(Component.literal("Spawns in: " + data.dimension()));

        // Ensure long feature names don't overlap the graph by shortening them visually if needed
        String name = data.featureName();
        if (name.length() > 18) name = name.substring(0, 18) + "...";

        // Text Info
        widgets.addText(Component.literal("§l" + name), 30, 4, 0x3F3F3F, false);
        widgets.addText(Component.literal("Y-Range: §9" + data.minY() + " §rto §9" + data.maxY()), 30, 16, 0x555555, false);
        widgets.addText(Component.literal("Avg Y: §2" + data.avgY() + " §r(±" + data.variance() + ")"), 30, 26, 0x555555, false);

        // Explicitly write the Dimension or Vein type
        String sourceText = data.dimension().substring(0, 1).toUpperCase() + data.dimension().substring(1);
        widgets.addText(Component.literal("In: §6" + sourceText), 30, 36, 0x555555, false);

        // Visual Height Bar Graph (-64 to 320)
        widgets.addDrawable(145, 5, 8, 50, (guiGraphics, mouseX, mouseY, delta) -> {
            guiGraphics.fill(145, 5, 153, 55, 0xFF333333); // Background track

            int mappedMin = 55 - (int) (((data.minY() + 64) / 384.0f) * 50);
            int mappedMax = 55 - (int) (((data.maxY() + 64) / 384.0f) * 50);

            mappedMin = Math.max(5, Math.min(55, mappedMin));
            mappedMax = Math.max(5, Math.min(55, mappedMax));

            guiGraphics.fill(146, mappedMax, 152, mappedMin, 0xFF55FF55); // Green range bar
        });
    }
}