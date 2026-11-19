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
        check("unknown version takes newest known", PackFormat.packFormat("26.1.2-R0.1-SNAPSHOT", 0)
                == PackFormat.latestKnownPackFormat());
        check("override wins", PackFormat.packFormat("26.1.2-R0.1-SNAPSHOT", 55) == 55);
        check("garbage version takes 46", PackFormat.packFormat("garbage", 0) == 46);
        check("26.x reads modern item representation",
                PackFormat.itemRepresentation("26.1.2-R0.1-SNAPSHOT")
                        == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL);
        check("1.21.0 reads legacy item representation",
                PackFormat.itemRepresentation("1.21.0-R0.1-SNAPSHOT")
                        == PackFormat.ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA);
    }

    private static void datapackFormatTable() {
        check("1.21.5 datapack format 71",
                VariantDatapackWriter.datapackFormatFor("1.21.5-R0.1-SNAPSHOT") == 71);
        check("1.21.6 datapack format 80",
                VariantDatapackWriter.datapackFormatFor("1.21.6-R0.1-SNAPSHOT") == 80);
        check("1.21.7 datapack format 81",
                VariantDatapackWriter.datapackFormatFor("1.21.7-R0.1-SNAPSHOT") == 81);
        check("26.1 gets a real datapack format",
                VariantDatapackWriter.datapackFormatFor("26.1.2-R0.1-SNAPSHOT") != -1);
        check("26.1 takes the newest known datapack format",
                VariantDatapackWriter.datapackFormatFor("26.1.2-R0.1-SNAPSHOT") == 81);
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
