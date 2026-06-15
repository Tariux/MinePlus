package com.mineplus.infrastructure.virtual;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Bukkit;

public final class TextureMaterialResolver {

    private static final Material FALLBACK = Material.WHITE_CONCRETE;

    private static final Map<String, Material> TEXTURE_MAP;

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
    }

    private static final Map<String, Material> OVERRIDES = new LinkedHashMap<>();

    public static void setOverride(String key, Material material) {
        if (key == null || material == null || !material.isBlock()) {
            return;
        }
        String normalizedKey = key.toLowerCase(Locale.ROOT).trim();
        if (normalizedKey.endsWith(".png")) {
            normalizedKey = normalizedKey.substring(0, normalizedKey.length() - 4);
        }
        OVERRIDES.put(normalizedKey, material);
    }

    public static Material resolve(String textureName) {
        if (textureName == null || textureName.isBlank()) {
            return FALLBACK;
        }

        String key = textureName.toLowerCase(Locale.ROOT).trim();

        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }

        if (OVERRIDES.containsKey(key)) {
            return OVERRIDES.get(key);
        }

        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }

        if (key.contains(":")) {
            key = key.substring(key.lastIndexOf(':') + 1);
        }
        
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf('/') + 1);
        }

        Material mapped = TEXTURE_MAP.get(key);
        if (mapped != null) {
            return mapped;
        }

        Material direct = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
        if (direct != null && direct.isBlock() && !direct.isAir()) {
            return direct;
        }

        if (key.contains("_")) {
            String[] parts = key.split("_");
            if (parts.length == 2) {
                String swappedKey = parts[1] + "_" + parts[0];
                
                Material swappedMapped = TEXTURE_MAP.get(swappedKey);
                if (swappedMapped != null) {
                    return swappedMapped;
                }
                
                Material swappedDirect = Material.matchMaterial(swappedKey.toUpperCase(Locale.ROOT));
                if (swappedDirect != null && swappedDirect.isBlock() && !swappedDirect.isAir()) {
                    return swappedDirect;
                }
            }
        }

        for (Map.Entry<String, Material> entry : TEXTURE_MAP.entrySet()) {
            String mapKey = entry.getKey();
            if (mapKey.contains(key) || key.contains(mapKey)) {
                return entry.getValue();
            }
        }

        Bukkit.getLogger().warning("[BbModelImporter] Could not resolve texture: '" + textureName + "'. Falling back to " + FALLBACK.name());
        return FALLBACK;
    }

    public static Material fallback() {
        return FALLBACK;
    }

    public static Set<String> supportedTextureNames() {
        return TEXTURE_MAP.keySet();
    }
}
