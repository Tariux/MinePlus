package com.mineplus.infrastructure.virtual;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import com.mineplus.util.DebugLogger;

/**
 * Filename-to-vanilla-material resolution pipeline (tiered):
 * <ol>
 *   <li>exact texture map (370 curated entries)</li>
 *   <li>direct {@link Material#matchMaterial(String)} (block, non-air)</li>
 *   <li>suffix-strip ({@code _top}, {@code _side}, ... ) retrying tiers 1–2</li>
 *   <li>legacy/Blockbench alias table</li>
 *   <li>token fuzzy: underscore-insensitive contains-match against map keys</li>
 *   <li>fallback ({@code WHITE_CONCRETE}) + recorded in the per-model report</li>
 * </ol>
 * Results are cached per texture name for the JVM lifetime.
 */
public final class TextureMaterialResolver {

    private static final Material FALLBACK = Material.WHITE_CONCRETE;

    private static final Map<String, Material> TEXTURE_MAP;
    private static final Map<String, Material> ALIASES;
    private static final ConcurrentHashMap<String, Resolution> RESOLVE_CACHE = new ConcurrentHashMap<>();

    /** Suffixes stripped in tier 3, longest first so {@code _side_overlay} style names behave. */
    private static final String[] STRIP_SUFFIXES = {
            "_top", "_side", "_bottom", "_front", "_back", "_left", "_right",
            "_inner", "_outer", "_base", "_0", "_1", "_2", "_3"
    };

    static {
        Map<String, Material> m = new LinkedHashMap<>();

        m.put("anvil_base", Material.ANVIL);
        m.put("anvil_top_damaged_0", Material.ANVIL);
        m.put("anvil_top_damaged_1", Material.CHIPPED_ANVIL);
        m.put("anvil_top_damaged_2", Material.DAMAGED_ANVIL);

        m.put("beacon", Material.BEACON);
        m.put("bedrock", Material.BEDROCK);

        m.put("bone_block_side", Material.BONE_BLOCK);
        m.put("bone_block_top", Material.BONE_BLOCK);

        m.put("bookshelf", Material.BOOKSHELF);

        m.put("brewing_stand", Material.BREWING_STAND);
        m.put("brewing_stand_base", Material.BREWING_STAND);

        m.put("brick", Material.BRICKS);

        m.put("cactus_bottom", Material.CACTUS);
        m.put("cactus_side", Material.CACTUS);
        m.put("cactus_top", Material.CACTUS);

        m.put("chain_command_block_back", Material.CHAIN_COMMAND_BLOCK);
        m.put("chain_command_block_conditional", Material.CHAIN_COMMAND_BLOCK);
        m.put("chain_command_block_front", Material.CHAIN_COMMAND_BLOCK);
        m.put("chain_command_block_side", Material.CHAIN_COMMAND_BLOCK);

        m.put("chorus_flower", Material.CHORUS_FLOWER);
        m.put("chorus_flower_dead", Material.CHORUS_FLOWER);
        m.put("chorus_plant", Material.CHORUS_PLANT);

        m.put("clay", Material.CLAY);

        m.put("coal_block", Material.COAL_BLOCK);
        m.put("coal_ore", Material.COAL_ORE);

        m.put("coarse_dirt", Material.COARSE_DIRT);
        m.put("cobblestone", Material.COBBLESTONE);
        m.put("cobblestone_mossy", Material.MOSSY_COBBLESTONE);

        m.put("command_block_back", Material.COMMAND_BLOCK);
        m.put("command_block_conditional", Material.COMMAND_BLOCK);
        m.put("command_block_front", Material.COMMAND_BLOCK);
        m.put("command_block_side", Material.COMMAND_BLOCK);

        m.put("concrete_black", Material.BLACK_CONCRETE);
        m.put("concrete_blue", Material.BLUE_CONCRETE);
        m.put("concrete_brown", Material.BROWN_CONCRETE);
        m.put("concrete_cyan", Material.CYAN_CONCRETE);
        m.put("concrete_gray", Material.GRAY_CONCRETE);
        m.put("concrete_green", Material.GREEN_CONCRETE);
        m.put("concrete_light_blue", Material.LIGHT_BLUE_CONCRETE);
        m.put("concrete_lime", Material.LIME_CONCRETE);
        m.put("concrete_magenta", Material.MAGENTA_CONCRETE);
        m.put("concrete_orange", Material.ORANGE_CONCRETE);
        m.put("concrete_pink", Material.PINK_CONCRETE);
        m.put("concrete_purple", Material.PURPLE_CONCRETE);
        m.put("concrete_red", Material.RED_CONCRETE);
        m.put("concrete_silver", Material.LIGHT_GRAY_CONCRETE);
        m.put("concrete_white", Material.WHITE_CONCRETE);
        m.put("concrete_yellow", Material.YELLOW_CONCRETE);

        m.put("concrete_powder_black", Material.BLACK_CONCRETE_POWDER);
        m.put("concrete_powder_blue", Material.BLUE_CONCRETE_POWDER);
        m.put("concrete_powder_brown", Material.BROWN_CONCRETE_POWDER);
        m.put("concrete_powder_cyan", Material.CYAN_CONCRETE_POWDER);
        m.put("concrete_powder_gray", Material.GRAY_CONCRETE_POWDER);
        m.put("concrete_powder_green", Material.GREEN_CONCRETE_POWDER);
        m.put("concrete_powder_light_blue", Material.LIGHT_BLUE_CONCRETE_POWDER);
        m.put("concrete_powder_lime", Material.LIME_CONCRETE_POWDER);
        m.put("concrete_powder_magenta", Material.MAGENTA_CONCRETE_POWDER);
        m.put("concrete_powder_orange", Material.ORANGE_CONCRETE_POWDER);
        m.put("concrete_powder_pink", Material.PINK_CONCRETE_POWDER);
        m.put("concrete_powder_purple", Material.PURPLE_CONCRETE_POWDER);
        m.put("concrete_powder_red", Material.RED_CONCRETE_POWDER);
        m.put("concrete_powder_silver", Material.LIGHT_GRAY_CONCRETE_POWDER);
        m.put("concrete_powder_white", Material.WHITE_CONCRETE_POWDER);
        m.put("concrete_powder_yellow", Material.YELLOW_CONCRETE_POWDER);

        m.put("crafting_table_front", Material.CRAFTING_TABLE);
        m.put("crafting_table_side", Material.CRAFTING_TABLE);
        m.put("crafting_table_top", Material.CRAFTING_TABLE);

        m.put("daylight_detector_inverted_top", Material.DAYLIGHT_DETECTOR);
        m.put("daylight_detector_side", Material.DAYLIGHT_DETECTOR);
        m.put("daylight_detector_top", Material.DAYLIGHT_DETECTOR);

        m.put("diamond_block", Material.DIAMOND_BLOCK);
        m.put("diamond_ore", Material.DIAMOND_ORE);

        m.put("dirt", Material.DIRT);
        m.put("dirt_podzol_side", Material.PODZOL);
        m.put("dirt_podzol_top", Material.PODZOL);

        m.put("dispenser_front_horizontal", Material.DISPENSER);
        m.put("dispenser_front_vertical", Material.DISPENSER);

        m.put("dragon_egg", Material.DRAGON_EGG);

        m.put("dropper_front_horizontal", Material.DROPPER);
        m.put("dropper_front_vertical", Material.DROPPER);

        m.put("emerald_block", Material.EMERALD_BLOCK);
        m.put("emerald_ore", Material.EMERALD_ORE);

        m.put("enchanting_table_bottom", Material.ENCHANTING_TABLE);
        m.put("enchanting_table_side", Material.ENCHANTING_TABLE);
        m.put("enchanting_table_top", Material.ENCHANTING_TABLE);

        m.put("end_bricks", Material.END_STONE_BRICKS);
        m.put("end_stone", Material.END_STONE);
        m.put("endframe_eye", Material.END_PORTAL_FRAME);
        m.put("endframe_side", Material.END_PORTAL_FRAME);
        m.put("endframe_top", Material.END_PORTAL_FRAME);

        m.put("farmland_dry", Material.FARMLAND);
        m.put("farmland_wet", Material.FARMLAND);

        m.put("furnace_front_off", Material.FURNACE);
        m.put("furnace_front_on", Material.FURNACE);
        m.put("furnace_side", Material.FURNACE);
        m.put("furnace_top", Material.FURNACE);

        m.put("glass", Material.GLASS);
        m.put("glass_black", Material.BLACK_STAINED_GLASS);
        m.put("glass_blue", Material.BLUE_STAINED_GLASS);
        m.put("glass_brown", Material.BROWN_STAINED_GLASS);
        m.put("glass_cyan", Material.CYAN_STAINED_GLASS);
        m.put("glass_gray", Material.GRAY_STAINED_GLASS);
        m.put("glass_green", Material.GREEN_STAINED_GLASS);
        m.put("glass_light_blue", Material.LIGHT_BLUE_STAINED_GLASS);
        m.put("glass_lime", Material.LIME_STAINED_GLASS);
        m.put("glass_magenta", Material.MAGENTA_STAINED_GLASS);
        m.put("glass_orange", Material.ORANGE_STAINED_GLASS);
        m.put("glass_pink", Material.PINK_STAINED_GLASS);
        m.put("glass_purple", Material.PURPLE_STAINED_GLASS);
        m.put("glass_red", Material.RED_STAINED_GLASS);
        m.put("glass_silver", Material.LIGHT_GRAY_STAINED_GLASS);
        m.put("glass_white", Material.WHITE_STAINED_GLASS);
        m.put("glass_yellow", Material.YELLOW_STAINED_GLASS);

        m.put("glass_pane_top", Material.GLASS_PANE);
        m.put("glass_pane_top_black", Material.BLACK_STAINED_GLASS_PANE);
        m.put("glass_pane_top_blue", Material.BLUE_STAINED_GLASS_PANE);
        m.put("glass_pane_top_brown", Material.BROWN_STAINED_GLASS_PANE);
        m.put("glass_pane_top_cyan", Material.CYAN_STAINED_GLASS_PANE);
        m.put("glass_pane_top_gray", Material.GRAY_STAINED_GLASS_PANE);
        m.put("glass_pane_top_green", Material.GREEN_STAINED_GLASS_PANE);
        m.put("glass_pane_top_light_blue", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        m.put("glass_pane_top_lime", Material.LIME_STAINED_GLASS_PANE);
        m.put("glass_pane_top_magenta", Material.MAGENTA_STAINED_GLASS_PANE);
        m.put("glass_pane_top_orange", Material.ORANGE_STAINED_GLASS_PANE);
        m.put("glass_pane_top_pink", Material.PINK_STAINED_GLASS_PANE);
        m.put("glass_pane_top_purple", Material.PURPLE_STAINED_GLASS_PANE);
        m.put("glass_pane_top_red", Material.RED_STAINED_GLASS_PANE);
        m.put("glass_pane_top_silver", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        m.put("glass_pane_top_white", Material.WHITE_STAINED_GLASS_PANE);
        m.put("glass_pane_top_yellow", Material.YELLOW_STAINED_GLASS_PANE);

        m.put("glazed_terracotta_black", Material.BLACK_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_blue", Material.BLUE_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_brown", Material.BROWN_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_cyan", Material.CYAN_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_gray", Material.GRAY_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_green", Material.GREEN_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_light_blue", Material.LIGHT_BLUE_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_lime", Material.LIME_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_magenta", Material.MAGENTA_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_orange", Material.ORANGE_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_pink", Material.PINK_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_purple", Material.PURPLE_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_red", Material.RED_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_silver", Material.LIGHT_GRAY_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_white", Material.WHITE_GLAZED_TERRACOTTA);
        m.put("glazed_terracotta_yellow", Material.YELLOW_GLAZED_TERRACOTTA);

        m.put("glowstone", Material.GLOWSTONE);

        m.put("gold_block", Material.GOLD_BLOCK);
        m.put("gold_ore", Material.GOLD_ORE);

        m.put("grass_path_side", Material.DIRT_PATH);
        m.put("grass_path_top", Material.DIRT_PATH);
        m.put("grass_side", Material.GRASS_BLOCK);
        m.put("grass_side_overlay", Material.GRASS_BLOCK);
        m.put("grass_side_snowed", Material.GRASS_BLOCK);
        m.put("grass_top", Material.GRASS_BLOCK);

        m.put("gravel", Material.GRAVEL);

        m.put("hardened_clay", Material.TERRACOTTA);
        m.put("hardened_clay_stained_black", Material.BLACK_TERRACOTTA);
        m.put("hardened_clay_stained_blue", Material.BLUE_TERRACOTTA);
        m.put("hardened_clay_stained_brown", Material.BROWN_TERRACOTTA);
        m.put("hardened_clay_stained_cyan", Material.CYAN_TERRACOTTA);
        m.put("hardened_clay_stained_gray", Material.GRAY_TERRACOTTA);
        m.put("hardened_clay_stained_green", Material.GREEN_TERRACOTTA);
        m.put("hardened_clay_stained_light_blue", Material.LIGHT_BLUE_TERRACOTTA);
        m.put("hardened_clay_stained_lime", Material.LIME_TERRACOTTA);
        m.put("hardened_clay_stained_magenta", Material.MAGENTA_TERRACOTTA);
        m.put("hardened_clay_stained_orange", Material.ORANGE_TERRACOTTA);
        m.put("hardened_clay_stained_pink", Material.PINK_TERRACOTTA);
        m.put("hardened_clay_stained_purple", Material.PURPLE_TERRACOTTA);
        m.put("hardened_clay_stained_red", Material.RED_TERRACOTTA);
        m.put("hardened_clay_stained_silver", Material.LIGHT_GRAY_TERRACOTTA);
        m.put("hardened_clay_stained_white", Material.WHITE_TERRACOTTA);
        m.put("hardened_clay_stained_yellow", Material.YELLOW_TERRACOTTA);

        m.put("hay_block_side", Material.HAY_BLOCK);
        m.put("hay_block_top", Material.HAY_BLOCK);

        m.put("hopper_inside", Material.HOPPER);
        m.put("hopper_outside", Material.HOPPER);
        m.put("hopper_top", Material.HOPPER);

        m.put("ice", Material.ICE);
        m.put("ice_packed", Material.PACKED_ICE);

        m.put("iron_block", Material.IRON_BLOCK);
        m.put("iron_ore", Material.IRON_ORE);
        m.put("iron_trapdoor", Material.IRON_TRAPDOOR);
        m.put("iron_bars", Material.IRON_BARS);

        m.put("jukebox_side", Material.JUKEBOX);
        m.put("jukebox_top", Material.JUKEBOX);

        m.put("lapis_block", Material.LAPIS_BLOCK);
        m.put("lapis_ore", Material.LAPIS_ORE);

        m.put("leaves_acacia", Material.ACACIA_LEAVES);
        m.put("leaves_big_oak", Material.DARK_OAK_LEAVES);
        m.put("leaves_birch", Material.BIRCH_LEAVES);
        m.put("leaves_jungle", Material.JUNGLE_LEAVES);
        m.put("leaves_oak", Material.OAK_LEAVES);
        m.put("leaves_spruce", Material.SPRUCE_LEAVES);

        m.put("log_acacia", Material.ACACIA_LOG);
        m.put("log_acacia_top", Material.ACACIA_LOG);
        m.put("log_big_oak", Material.DARK_OAK_LOG);
        m.put("log_big_oak_top", Material.DARK_OAK_LOG);
        m.put("log_birch", Material.BIRCH_LOG);
        m.put("log_birch_top", Material.BIRCH_LOG);
        m.put("log_jungle", Material.JUNGLE_LOG);
        m.put("log_jungle_top", Material.JUNGLE_LOG);
        m.put("log_oak", Material.OAK_LOG);
        m.put("log_oak_top", Material.OAK_LOG);
        m.put("log_spruce", Material.SPRUCE_LOG);
        m.put("log_spruce_top", Material.SPRUCE_LOG);

        m.put("magma", Material.MAGMA_BLOCK);

        m.put("melon_side", Material.MELON);
        m.put("melon_top", Material.MELON);

        m.put("mob_spawner", Material.SPAWNER);

        m.put("mushroom_block_inside", Material.MUSHROOM_STEM);
        m.put("mushroom_block_skin_brown", Material.BROWN_MUSHROOM_BLOCK);
        m.put("mushroom_block_skin_red", Material.RED_MUSHROOM_BLOCK);
        m.put("mushroom_block_skin_stem", Material.MUSHROOM_STEM);

        m.put("mycelium_side", Material.MYCELIUM);
        m.put("mycelium_top", Material.MYCELIUM);

        m.put("nether_brick", Material.NETHER_BRICKS);
        m.put("nether_wart_block", Material.NETHER_WART_BLOCK);
        m.put("netherrack", Material.NETHERRACK);

        m.put("noteblock", Material.NOTE_BLOCK);

        m.put("observer_back", Material.OBSERVER);
        m.put("observer_back_lit", Material.OBSERVER);
        m.put("observer_front", Material.OBSERVER);
        m.put("observer_side", Material.OBSERVER);
        m.put("observer_top", Material.OBSERVER);

        m.put("obsidian", Material.OBSIDIAN);

        m.put("piston_bottom", Material.PISTON);
        m.put("piston_inner", Material.PISTON);
        m.put("piston_side", Material.PISTON);
        m.put("piston_top_normal", Material.PISTON);
        m.put("piston_top_sticky", Material.STICKY_PISTON);

        m.put("planks_acacia", Material.ACACIA_PLANKS);
        m.put("planks_big_oak", Material.DARK_OAK_PLANKS);
        m.put("planks_birch", Material.BIRCH_PLANKS);
        m.put("planks_jungle", Material.JUNGLE_PLANKS);
        m.put("planks_oak", Material.OAK_PLANKS);
        m.put("planks_spruce", Material.SPRUCE_PLANKS);

        m.put("prismarine_bricks", Material.PRISMARINE_BRICKS);
        m.put("prismarine_dark", Material.DARK_PRISMARINE);
        m.put("prismarine_rough", Material.PRISMARINE);

        m.put("pumpkin_face_off", Material.CARVED_PUMPKIN);
        m.put("pumpkin_face_on", Material.JACK_O_LANTERN);
        m.put("pumpkin_side", Material.PUMPKIN);
        m.put("pumpkin_top", Material.PUMPKIN);

        m.put("purpur_block", Material.PURPUR_BLOCK);
        m.put("purpur_pillar", Material.PURPUR_PILLAR);
        m.put("purpur_pillar_top", Material.PURPUR_PILLAR);

        m.put("quartz_block_bottom", Material.QUARTZ_BLOCK);
        m.put("quartz_block_chiseled", Material.CHISELED_QUARTZ_BLOCK);
        m.put("quartz_block_chiseled_top", Material.CHISELED_QUARTZ_BLOCK);
        m.put("quartz_block_lines", Material.QUARTZ_PILLAR);
        m.put("quartz_block_lines_top", Material.QUARTZ_PILLAR);
        m.put("quartz_block_side", Material.QUARTZ_BLOCK);
        m.put("quartz_block_top", Material.QUARTZ_BLOCK);
        m.put("quartz_ore", Material.NETHER_QUARTZ_ORE);

        m.put("red_nether_brick", Material.RED_NETHER_BRICKS);
        m.put("red_sand", Material.RED_SAND);
        m.put("red_sandstone_bottom", Material.RED_SANDSTONE);
        m.put("red_sandstone_carved", Material.CHISELED_RED_SANDSTONE);
        m.put("red_sandstone_normal", Material.RED_SANDSTONE);
        m.put("red_sandstone_smooth", Material.CUT_RED_SANDSTONE);
        m.put("red_sandstone_top", Material.RED_SANDSTONE);

        m.put("redstone_block", Material.REDSTONE_BLOCK);
        m.put("redstone_lamp_off", Material.REDSTONE_LAMP);
        m.put("redstone_lamp_on", Material.REDSTONE_LAMP);
        m.put("redstone_ore", Material.REDSTONE_ORE);

        m.put("repeating_command_block_back", Material.REPEATING_COMMAND_BLOCK);
        m.put("repeating_command_block_conditional", Material.REPEATING_COMMAND_BLOCK);
        m.put("repeating_command_block_front", Material.REPEATING_COMMAND_BLOCK);
        m.put("repeating_command_block_side", Material.REPEATING_COMMAND_BLOCK);

        m.put("sand", Material.SAND);
        m.put("sandstone_bottom", Material.SANDSTONE);
        m.put("sandstone_carved", Material.CHISELED_SANDSTONE);
        m.put("sandstone_normal", Material.SANDSTONE);
        m.put("sandstone_smooth", Material.CUT_SANDSTONE);
        m.put("sandstone_top", Material.SANDSTONE);

        m.put("sea_lantern", Material.SEA_LANTERN);

        m.put("shulker_top_black", Material.BLACK_SHULKER_BOX);
        m.put("shulker_top_blue", Material.BLUE_SHULKER_BOX);
        m.put("shulker_top_brown", Material.BROWN_SHULKER_BOX);
        m.put("shulker_top_cyan", Material.CYAN_SHULKER_BOX);
        m.put("shulker_top_gray", Material.GRAY_SHULKER_BOX);
        m.put("shulker_top_green", Material.GREEN_SHULKER_BOX);
        m.put("shulker_top_light_blue", Material.LIGHT_BLUE_SHULKER_BOX);
        m.put("shulker_top_lime", Material.LIME_SHULKER_BOX);
        m.put("shulker_top_magenta", Material.MAGENTA_SHULKER_BOX);
        m.put("shulker_top_orange", Material.ORANGE_SHULKER_BOX);
        m.put("shulker_top_pink", Material.PINK_SHULKER_BOX);
        m.put("shulker_top_purple", Material.PURPLE_SHULKER_BOX);
        m.put("shulker_top_red", Material.RED_SHULKER_BOX);
        m.put("shulker_top_silver", Material.LIGHT_GRAY_SHULKER_BOX);
        m.put("shulker_top_white", Material.WHITE_SHULKER_BOX);
        m.put("shulker_top_yellow", Material.YELLOW_SHULKER_BOX);

        m.put("slime", Material.SLIME_BLOCK);
        m.put("snow", Material.SNOW_BLOCK);
        m.put("soul_sand", Material.SOUL_SAND);
        m.put("sponge", Material.SPONGE);
        m.put("sponge_wet", Material.WET_SPONGE);

        m.put("stone", Material.STONE);
        m.put("stone_andesite", Material.ANDESITE);
        m.put("stone_andesite_smooth", Material.POLISHED_ANDESITE);
        m.put("stone_diorite", Material.DIORITE);
        m.put("stone_diorite_smooth", Material.POLISHED_DIORITE);
        m.put("stone_granite", Material.GRANITE);
        m.put("stone_granite_smooth", Material.POLISHED_GRANITE);
        m.put("stone_slab_side", Material.SMOOTH_STONE);
        m.put("stone_slab_top", Material.SMOOTH_STONE);

        m.put("stonebrick", Material.STONE_BRICKS);
        m.put("stonebrick_carved", Material.CHISELED_STONE_BRICKS);
        m.put("stonebrick_cracked", Material.CRACKED_STONE_BRICKS);
        m.put("stonebrick_mossy", Material.MOSSY_STONE_BRICKS);

        m.put("structure_block", Material.STRUCTURE_BLOCK);
        m.put("structure_block_corner", Material.STRUCTURE_BLOCK);
        m.put("structure_block_data", Material.STRUCTURE_BLOCK);
        m.put("structure_block_load", Material.STRUCTURE_BLOCK);
        m.put("structure_block_save", Material.STRUCTURE_BLOCK);

        m.put("tnt_bottom", Material.TNT);
        m.put("tnt_side", Material.TNT);
        m.put("tnt_top", Material.TNT);

        m.put("wool_colored_black", Material.BLACK_WOOL);
        m.put("wool_colored_blue", Material.BLUE_WOOL);
        m.put("wool_colored_brown", Material.BROWN_WOOL);
        m.put("wool_colored_cyan", Material.CYAN_WOOL);
        m.put("wool_colored_gray", Material.GRAY_WOOL);
        m.put("wool_colored_green", Material.GREEN_WOOL);
        m.put("wool_colored_light_blue", Material.LIGHT_BLUE_WOOL);
        m.put("wool_colored_lime", Material.LIME_WOOL);
        m.put("wool_colored_magenta", Material.MAGENTA_WOOL);
        m.put("wool_colored_orange", Material.ORANGE_WOOL);
        m.put("wool_colored_pink", Material.PINK_WOOL);
        m.put("wool_colored_purple", Material.PURPLE_WOOL);
        m.put("wool_colored_red", Material.RED_WOOL);
        m.put("wool_colored_silver", Material.LIGHT_GRAY_WOOL);
        m.put("wool_colored_white", Material.WHITE_WOOL);
        m.put("wool_colored_yellow", Material.YELLOW_WOOL);

        TEXTURE_MAP = Collections.unmodifiableMap(m);

        Map<String, Material> a = new LinkedHashMap<>();
        a.put("wool_colored_white", Material.WHITE_WOOL);
        a.put("wool_colored_orange", Material.ORANGE_WOOL);
        a.put("wool_colored_magenta", Material.MAGENTA_WOOL);
        a.put("wool_colored_light_blue", Material.LIGHT_BLUE_WOOL);
        a.put("wool_colored_yellow", Material.YELLOW_WOOL);
        a.put("wool_colored_lime", Material.LIME_WOOL);
        a.put("wool_colored_pink", Material.PINK_WOOL);
        a.put("wool_colored_gray", Material.GRAY_WOOL);
        a.put("wool_colored_light_gray", Material.LIGHT_GRAY_WOOL);
        a.put("wool_colored_cyan", Material.CYAN_WOOL);
        a.put("wool_colored_purple", Material.PURPLE_WOOL);
        a.put("wool_colored_blue", Material.BLUE_WOOL);
        a.put("wool_colored_brown", Material.BROWN_WOOL);
        a.put("wool_colored_green", Material.GREEN_WOOL);
        a.put("wool_colored_red", Material.RED_WOOL);
        a.put("wool_colored_black", Material.BLACK_WOOL);
        a.put("planks", Material.OAK_PLANKS);
        a.put("log", Material.OAK_LOG);
        a.put("sapling", Material.OAK_SAPLING);
        a.put("leaves", Material.OAK_LEAVES);
        a.put("door", Material.OAK_DOOR);
        a.put("door_upper", Material.OAK_DOOR);
        a.put("door_lower", Material.OAK_DOOR);
        a.put("trapdoor", Material.OAK_TRAPDOOR);
        a.put("fence", Material.OAK_FENCE);
        a.put("fence_gate", Material.OAK_FENCE_GATE);
        a.put("stairs", Material.OAK_STAIRS);
        a.put("slab", Material.OAK_SLAB);
        a.put("button", Material.OAK_BUTTON);
        a.put("pressure_plate", Material.OAK_PRESSURE_PLATE);
        a.put("sign", Material.OAK_SIGN);
        a.put("boat", Material.OAK_BOAT);
        a.put("hardened_clay_stained_white", Material.WHITE_TERRACOTTA);
        a.put("hardened_clay_stained_orange", Material.ORANGE_TERRACOTTA);
        a.put("hardened_clay_stained_magenta", Material.MAGENTA_TERRACOTTA);
        a.put("hardened_clay_stained_light_blue", Material.LIGHT_BLUE_TERRACOTTA);
        a.put("hardened_clay_stained_yellow", Material.YELLOW_TERRACOTTA);
        a.put("hardened_clay_stained_lime", Material.LIME_TERRACOTTA);
        a.put("hardened_clay_stained_pink", Material.PINK_TERRACOTTA);
        a.put("hardened_clay_stained_gray", Material.GRAY_TERRACOTTA);
        a.put("hardened_clay_stained_light_gray", Material.LIGHT_GRAY_TERRACOTTA);
        a.put("hardened_clay_stained_cyan", Material.CYAN_TERRACOTTA);
        a.put("hardened_clay_stained_purple", Material.PURPLE_TERRACOTTA);
        a.put("hardened_clay_stained_blue", Material.BLUE_TERRACOTTA);
        a.put("hardened_clay_stained_brown", Material.BROWN_TERRACOTTA);
        a.put("hardened_clay_stained_green", Material.GREEN_TERRACOTTA);
        a.put("hardened_clay_stained_red", Material.RED_TERRACOTTA);
        a.put("hardened_clay_stained_black", Material.BLACK_TERRACOTTA);
        a.put("stained_glass_white", Material.WHITE_STAINED_GLASS);
        a.put("stained_glass_pane_white", Material.WHITE_STAINED_GLASS_PANE);
        a.put("carpet_white", Material.WHITE_CARPET);
        a.put("dye_white", Material.WHITE_DYE);
        a.put("brick_block", Material.BRICKS);
        a.put("stonebrick", Material.STONE_BRICKS);
        a.put("stone_brick", Material.STONE_BRICKS);
        a.put("wood", Material.OAK_PLANKS);
        a.put("wooden", Material.OAK_PLANKS);
        a.put("double_plant", Material.SUNFLOWER);
        a.put("tall_grass", Material.TALL_GRASS);
        a.put("dead_bush", Material.DEAD_BUSH);
        a.put("mushroom_red", Material.RED_MUSHROOM);
        a.put("mushroom_brown", Material.BROWN_MUSHROOM);
        a.put("torch_on", Material.TORCH);
        a.put("redstone_dust_dot", Material.REDSTONE);
        a.put("redstone_dust_line0", Material.REDSTONE);
        a.put("redstone_dust_line1", Material.REDSTONE);
        a.put("water_still", Material.WATER);
        a.put("water_flow", Material.WATER);
        a.put("lava_still", Material.LAVA);
        a.put("lava_flow", Material.LAVA);
        a.put("fire_layer_0", Material.FIRE);
        a.put("fire_layer_1", Material.FIRE);
        a.put("destroy_stage_0", Material.AIR);
        a.put("destroy_stage_1", Material.AIR);
        a.put("destroy_stage_2", Material.AIR);
        a.put("destroy_stage_3", Material.AIR);
        a.put("destroy_stage_4", Material.AIR);
        a.put("destroy_stage_5", Material.AIR);
        a.put("destroy_stage_6", Material.AIR);
        a.put("destroy_stage_7", Material.AIR);
        a.put("destroy_stage_8", Material.AIR);
        a.put("destroy_stage_9", Material.AIR);
        a.put("particle_generic", Material.AIR);
        a.put("missingno", Material.AIR);
        a.put("missing_model", Material.AIR);
        ALIASES = Collections.unmodifiableMap(a);
    }

    /** Resolution outcome for diagnostics. */
    public record Resolution(String textureName, Material material, int tier) {

        public boolean isFallback() {
            return material == FALLBACK;
        }

        public String tierName() {
            return switch (tier) {
                case 1 -> "map";
                case 2 -> "direct";
                case 3 -> "suffix-strip";
                case 4 -> "alias";
                case 5 -> "fuzzy";
                default -> "fallback";
            };
        }
    }

    public static Material resolve(String textureName) {
        return resolveDetailed(textureName).material();
    }

    public static Resolution resolveDetailed(String textureName) {
        if (textureName == null || textureName.isBlank()) {
            return new Resolution(textureName, FALLBACK, 0);
        }
        return RESOLVE_CACHE.computeIfAbsent(textureName, TextureMaterialResolver::resolveUncached);
    }

    private static Resolution resolveUncached(String textureName) {
        String key = normalize(textureName);
        if (key.isEmpty()) {
            return new Resolution(textureName, FALLBACK, 0);
        }

        Material mapped = TEXTURE_MAP.get(key);
        if (mapped != null) {
            return new Resolution(textureName, mapped, 1);
        }

        Material direct = matchBlock(key);
        if (direct != null) {
            return new Resolution(textureName, direct, 2);
        }

        for (String suffix : STRIP_SUFFIXES) {
            if (key.endsWith(suffix) && key.length() > suffix.length()) {
                String stripped = key.substring(0, key.length() - suffix.length());
                Material strippedMapped = TEXTURE_MAP.get(stripped);
                if (strippedMapped != null) {
                    return new Resolution(textureName, strippedMapped, 3);
                }
                Material strippedDirect = matchBlock(stripped);
                if (strippedDirect != null) {
                    return new Resolution(textureName, strippedDirect, 3);
                }
            }
        }

        Material aliased = ALIASES.get(key);
        if (aliased != null && aliased != Material.AIR) {
            return new Resolution(textureName, aliased, 4);
        }

        Resolution fuzzy = fuzzyResolve(textureName, key);
        if (fuzzy != null) {
            return fuzzy;
        }

        DebugLogger.warning("[TextureMaterialResolver] Could not resolve texture: '" + textureName
                + "'. Falling back to " + FALLBACK.name());
        return new Resolution(textureName, FALLBACK, 0);
    }

    private static Resolution fuzzyResolve(String originalName, String key) {
        String flatKey = key.replace("_", "");
        if (flatKey.isEmpty()) {
            return null;
        }

        Material best = null;
        String bestKeyName = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<String, Material> entry : TEXTURE_MAP.entrySet()) {
            String mapKey = entry.getKey();
            String flatMapKey = mapKey.replace("_", "");
            boolean candidate = false;
            int rank = Integer.MAX_VALUE;
            if (flatMapKey.contains(flatKey) || flatKey.contains(flatMapKey)) {
                candidate = true;
                rank = Math.abs(flatKey.length() - flatMapKey.length());
            }
            if (candidate && rank < bestRank) {
                bestRank = rank;
                best = entry.getValue();
                bestKeyName = mapKey;
            }
        }
        if (best == null) {
            return null;
        }
        DebugLogger.info("[TextureMaterialResolver] fuzzy-resolved '" + originalName + "' via '" + bestKeyName + "'.");
        return new Resolution(originalName, best, 5);
    }

    private static Material matchBlock(String key) {
        Material matched = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
        if (matched == null || !matched.isBlock() || matched.isAir()) {
            return null;
        }
        return matched;
    }

    private static String normalize(String textureName) {
        String key = textureName.toLowerCase(Locale.ROOT).trim();
        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }
        if (key.endsWith(".mcmeta")) {
            key = key.substring(0, key.length() - 7);
        }
        if (key.contains(":")) {
            key = key.substring(key.lastIndexOf(':') + 1);
        }
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf('/') + 1);
        }
        return key;
    }

    public static Material fallback() {
        return FALLBACK;
    }

    public static Set<String> supportedTextureNames() {
        return TEXTURE_MAP.keySet();
    }
}
