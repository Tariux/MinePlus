package com.mineplus.test;

import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.BehaviorContext;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * Guards the one-jar promise across 1.21 to 26.3 at the bytecode level.
 *
 * <p>The plugin compiles against the 1.21.1 API, where {@code Attribute},
 * {@code Biome}, {@code Sound}, {@code Art} and {@code Fluid} are enums. From
 * 1.21.3 on they are interfaces and the {@code GENERIC_} attribute constants
 * are gone, so a call made directly on one of those types (or a read of a
 * {@code GENERIC_} constant) links on 1.21.1 and fails on every later server
 * unless that server's load-time rewriter happens to patch it. This runner
 * reads the compiled classes' constant pools and fails when such a reference
 * exists; it needs no server and no extra library.
 */
public final class ApiShapeGuardTest {

    /** Enums in the 1.21.1 API that are interfaces from 1.21.3 on. */
    private static final Set<String> RESHAPED = Set.of(
            "org/bukkit/attribute/Attribute",
            "org/bukkit/block/Biome",
            "org/bukkit/Sound",
            "org/bukkit/Art",
            "org/bukkit/Fluid");

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int scanned = scanCompiledClasses();
        behaviourChecks();
        if (failures.isEmpty()) {
            System.out.println("ApiShapeGuardTest: all checks passed (" + scanned + " classes scanned)");
            return;
        }
        System.out.println("ApiShapeGuardTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    private static int scanCompiledClasses() throws IOException, URISyntaxException {
        Path root = Path.of(Sounds.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(root)) {
            classFiles = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        check(!classFiles.isEmpty(), "no compiled classes found under " + root);
        for (Path classFile : classFiles) {
            try (InputStream in = Files.newInputStream(classFile)) {
                scan(root.relativize(classFile).toString(), new DataInputStream(in));
            }
        }
        return classFiles.size();
    }

    /** Walks one class file's constant pool (JVMS 4.4) for forbidden references. */
    private static void scan(String label, DataInputStream in) throws IOException {
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        Map<Integer, Integer> classNameIndex = new HashMap<>();
        Map<Integer, int[]> nameAndType = new HashMap<>();
        List<int[]> memberRefs = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8.put(index, in.readUTF());
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    index++;
                }
                case 7 -> classNameIndex.put(index, in.readUnsignedShort());
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11 -> memberRefs.add(new int[]{tag, in.readUnsignedShort(), in.readUnsignedShort()});
                case 12 -> nameAndType.put(index, new int[]{in.readUnsignedShort(), in.readUnsignedShort()});
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " in " + label);
            }
        }
        for (int[] ref : memberRefs) {
            String owner = utf8.get(classNameIndex.get(ref[1]));
            String member = utf8.get(nameAndType.get(ref[2])[0]);
            if (!RESHAPED.contains(owner)) {
                continue;
            }
            if (ref[0] == 10) {
                failures.add(label + " calls " + owner + "." + member
                        + "() as a class method; the type is an interface from 1.21.3 on");
            }
            if (ref[0] == 9 && member.startsWith("GENERIC_")) {
                failures.add(label + " reads " + owner + "." + member + ", which was renamed in 1.21.3");
            }
        }
    }

    private static void behaviourChecks() {
        check(Sounds.enumSound("BLOCK_NOTE_BLOCK_HAT") == org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT,
                "a sound constant name must resolve to its constant");
        check(Sounds.enumSound("NOT_A_REAL_SOUND") == null, "an unknown sound constant must resolve to null");
        check(Sounds.enumSound("mypack:shot") == null, "a namespaced key is not a constant name");
        check(Sounds.enumSound("block.note_block.hat") == null, "a vanilla key is not a constant name");

        check(BehaviorContext.Planting.saplingFor(Biome.BIRCH_FOREST) == Material.BIRCH_SAPLING,
                "birch forest must plant birch");
        check(BehaviorContext.Planting.saplingFor(Biome.DARK_FOREST) == Material.DARK_OAK_SAPLING,
                "dark forest must plant dark oak");
        check(BehaviorContext.Planting.saplingFor(Biome.PLAINS) == Material.OAK_SAPLING, "plains must plant oak");
        check(BehaviorContext.Planting.saplingFor(Biome.DESERT) == null, "desert has no sapling");
        check(BehaviorContext.Planting.saplingFor(null) == null, "no biome has no sapling");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }
}
