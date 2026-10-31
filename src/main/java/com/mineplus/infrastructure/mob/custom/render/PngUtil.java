package com.mineplus.infrastructure.mob.custom.render;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal PNG utilities for the mob render pipeline: dimension reads and the
 * neutral placeholder texture a broken or missing texture is repaired with.
 *
 * <p>The placeholder is a solid, medium gray — deliberately <b>not</b> the
 * client's magenta/black missing-texture colors, so a repaired texture reads
 * as "something is not textured here" instead of an error flash. The encoder
 * writes a minimal RGBA8 PNG (no filtering, one IDAT); its output is checked
 * against the parser's own PNG reader, so the two can never disagree.</p>
 */
public final class PngUtil {

    /** The neutral repair color: slate gray, not magenta and not black. */
    public static final int PLACEHOLDER_RGB = 0x8A9199;

    private static final byte[] SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private PngUtil() {
    }

    /** True when the bytes begin with the PNG signature. */
    public static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bytes[i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code {width, height}} from the IHDR chunk, or {@code null} when the
     * bytes are not a readable PNG.
     */
    public static int[] dimensions(byte[] bytes) {
        if (!isPng(bytes) || bytes.length < 24) {
            return null;
        }
        int width = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16)
                | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
        int height = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16)
                | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    /** A solid placeholder PNG of the requested size (clamped to 1..1024 per side). */
    public static byte[] neutralPlaceholder(int width, int height) {
        int w = Math.max(1, Math.min(1024, width));
        int h = Math.max(1, Math.min(1024, height));
        int stride = w * 4;
        byte[] raw = new byte[(stride + 1) * h];
        for (int y = 0; y < h; y++) {
            int row = y * (stride + 1);
            raw[row] = 0;
            for (int x = 0; x < w; x++) {
                int pixel = row + 1 + x * 4;
                raw[pixel] = (byte) (PLACEHOLDER_RGB >> 16);
                raw[pixel + 1] = (byte) (PLACEHOLDER_RGB >> 8);
                raw[pixel + 2] = (byte) PLACEHOLDER_RGB;
                raw[pixel + 3] = (byte) 0xFF;
            }
        }
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.size() + 128);
            out.writeBytes(SIGNATURE);
            byte[] ihdr = new byte[13];
            writeInt(ihdr, 0, w);
            writeInt(ihdr, 4, h);
            ihdr[8] = 8;   // bit depth
            ihdr[9] = 6;   // color type RGBA
            ihdr[10] = 0;  // compression
            ihdr[11] = 0;  // filter
            ihdr[12] = 0;  // interlace
            writeChunk(out, "IHDR", ihdr);
            writeChunk(out, "IDAT", compressed.toByteArray());
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        writeInt(length, 0, data.length);
        out.writeBytes(length);
        byte[] typeBytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            typeBytes[i] = (byte) type.charAt(i);
        }
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        writeInt(crcBytes, 0, (int) crc.getValue());
        out.writeBytes(crcBytes);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }
}
