package com.mineplus.test;

import com.mineplus.infrastructure.mob.custom.render.VariantDatapackWriter;
import com.mineplus.pack.PackFormat;
import com.mineplus.util.ServerVersion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-Java runner for the version backbone (no Bukkit server): calendar-version
 * parsing, milestone comparisons, pack-format tables and datapack formats.
 * Run {@code main} after compiling; a non-zero exit code means a check failed.
 */
public final class CompatVersionTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        versionParsing();
        milestoneComparisons();
        packFormatTable();
        packMetaShapes();
        datapackFormatTable();
        if (failures.isEmpty()) {
            System.out.println("CompatVersionTest: all checks passed");
            return;
        }
        System.out.println("CompatVersionTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void versionParsing() {
        check("26.1.2 paper suffix parses", Arrays.equals(
                ServerVersion.parseVersion("26.1.2-R0.1-SNAPSHOT"), new int[]{26, 1, 2}));
        check("26.1 build suffix parses", Arrays.equals(
                ServerVersion.parseVersion("26.1-build.23"), new int[]{26, 1, 0}));
        check("1.21.4 parses", Arrays.equals(
                ServerVersion.parseVersion("1.21.4-R0.1-SNAPSHOT"), new int[]{1, 21, 4}));
        check("1.21 parses", Arrays.equals(
                ServerVersion.parseVersion("1.21"), new int[]{1, 21, 0}));
        check("null parses to zero", Arrays.equals(
                ServerVersion.parseVersion(null), new int[]{0, 0, 0}));
        check("garbage parses to zero", Arrays.equals(
                ServerVersion.parseVersion("unknown"), new int[]{0, 0, 0}));
    }

    private static void milestoneComparisons() {
        check("1.21.4 at least 1.21.4", ServerVersion.atLeast(new int[]{1, 21, 4}, 1, 21, 4));
        check("26.1 at least 1.21.4", ServerVersion.atLeast(new int[]{26, 1, 0}, 1, 21, 4));
        check("1.21.3 not at least 1.21.4", !ServerVersion.atLeast(new int[]{1, 21, 3}, 1, 21, 4));
        check("26.1 at least 26.1", ServerVersion.atLeast(new int[]{26, 1, 0}, 26, 1, 0));
        check("26.2 at least 26.1", ServerVersion.atLeast(new int[]{26, 2, 0}, 26, 1, 0));
        check("26.1 not at least 26.1.1", !ServerVersion.atLeast(new int[]{26, 1, 0}, 26, 1, 1));
        check("1.21.9 not at least 26.1", !ServerVersion.atLeast(new int[]{1, 21, 9}, 26, 1, 0));
    }

    private static void packFormatTable() {
        check("1.21.0 pack format 34", PackFormat.packFormat("1.21.0-R0.1-SNAPSHOT", 0) == 34);
        check("1.21.4 pack format 46", PackFormat.packFormat("1.21.4-R0.1-SNAPSHOT", 0) == 46);
        check("1.21.7 pack format 64", PackFormat.packFormat("1.21.7-R0.1-SNAPSHOT", 0) == 64);
        check("1.21.9 pack format 69", PackFormat.packFormat("1.21.9-R0.1-SNAPSHOT", 0) == 69);
        check("1.21.11 pack format 75", PackFormat.packFormat("1.21.11-R0.1-SNAPSHOT", 0) == 75);
        check("26.1 pack format 84", PackFormat.packFormat("26.1.2-R0.1-SNAPSHOT", 0) == 84);
        check("26.2 pack format 88", PackFormat.packFormat("26.2-R0.1-SNAPSHOT", 0) == 88);
        check("26.3 pack format 97.1", PackFormat.packFormat("26.3-R0.1-SNAPSHOT", 0) == 97.1);
        check("unknown future year takes newest known", PackFormat.packFormat("27.1-R0.1-SNAPSHOT", 0)
                == PackFormat.latestKnownPackFormat());
        check("override wins", PackFormat.packFormat("26.1.2-R0.1-SNAPSHOT", 55) == 55);
        check("garbage version takes 46", PackFormat.packFormat("garbage", 0) == 46);
        check("1.21.8 keeps the pack_format era", !PackFormat.usesFormatRange("1.21.8-R0.1-SNAPSHOT"));
        check("1.21.9 starts the min/max format era", PackFormat.usesFormatRange("1.21.9-R0.1-SNAPSHOT"));
        check("26.x uses the min/max format era", PackFormat.usesFormatRange("26.1.2-R0.1-SNAPSHOT"));
        check("26.x reads modern item representation",
                PackFormat.itemRepresentation("26.1.2-R0.1-SNAPSHOT")
                        == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL);
        check("1.21.0 reads legacy item representation",
                PackFormat.itemRepresentation("1.21.0-R0.1-SNAPSHOT")
                        == PackFormat.ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA);
    }

    private static void packMetaShapes() {
        String modern = com.mineplus.pack.compile.PackCompiler.packMetaJson("26.1.2-R0.1-SNAPSHOT", 84);
        check("modern era mcmeta carries min_format", modern.contains("min_format"));
        check("modern era mcmeta carries max_format", modern.contains("max_format"));
        check("modern era mcmeta omits pack_format", !modern.contains("pack_format"));
        check("modern era mcmeta ceilings at the newest known", modern.contains("97.1"));
        String legacy = com.mineplus.pack.compile.PackCompiler.packMetaJson("1.21.4-R0.1-SNAPSHOT", 46);
        check("legacy era mcmeta carries pack_format", legacy.contains("pack_format"));
        check("legacy era mcmeta carries supported_formats", legacy.contains("supported_formats"));
        check("legacy era mcmeta omits min_format", !legacy.contains("min_format"));
    }

    private static void datapackFormatTable() {
        check("1.21.5 datapack format 71",
                VariantDatapackWriter.datapackFormatFor("1.21.5-R0.1-SNAPSHOT") == 71);
        check("1.21.6 datapack format 80",
                VariantDatapackWriter.datapackFormatFor("1.21.6-R0.1-SNAPSHOT") == 80);
        check("1.21.7 datapack format 81",
                VariantDatapackWriter.datapackFormatFor("1.21.7-R0.1-SNAPSHOT") == 81);
        check("1.21.9 datapack format 88",
                VariantDatapackWriter.datapackFormatFor("1.21.9-R0.1-SNAPSHOT") == 88);
        check("1.21.11 datapack format 94",
                VariantDatapackWriter.datapackFormatFor("1.21.11-R0.1-SNAPSHOT") == 94);
        check("26.1 datapack format 101",
                VariantDatapackWriter.datapackFormatFor("26.1.2-R0.1-SNAPSHOT") == 101);
        check("26.2 datapack format 107",
                VariantDatapackWriter.datapackFormatFor("26.2-R0.1-SNAPSHOT") == 107);
        check("26.3 datapack format 121",
                VariantDatapackWriter.datapackFormatFor("26.3-R0.1-SNAPSHOT") == 121);
        check("pre-1.21.5 has no datapack format",
                VariantDatapackWriter.datapackFormatFor("1.21.4-R0.1-SNAPSHOT") == -1);
        check("unparseable has no datapack format",
                VariantDatapackWriter.datapackFormatFor("garbage") == -1);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
