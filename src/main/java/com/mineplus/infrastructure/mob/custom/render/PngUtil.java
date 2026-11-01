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
        byte[] pixels = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            pixels[i * 4] = (byte) (PLACEHOLDER_RGB >> 16);
            pixels[i * 4 + 1] = (byte) (PLACEHOLDER_RGB >> 8);
            pixels[i * 4 + 2] = (byte) PLACEHOLDER_RGB;
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        return encodeRgba(w, h, pixels);
    }

    /**
     * The bundled ground-shadow texture: black with a smooth radial alpha
     * falloff, so a rigged mob reads as standing on the ground instead of
     * floating over it. Alpha peaks at the center and reaches zero exactly at
     * the unit circle, which keeps the plate's edge out of the picture.
     */
    public static byte[] softShadowTexture(int size) {
        int s = Math.max(8, Math.min(128, size));
        byte[] pixels = new byte[s * s * 4];
        double center = (s - 1) / 2.0;
        for (int y = 0; y < s; y++) {
            double dy = (y - center) / center;
            for (int x = 0; x < s; x++) {
                double dx = (x - center) / center;
                double distance = Math.sqrt(dx * dx + dy * dy);
                int index = (y * s + x) * 4;
                pixels[index] = 0;
                pixels[index + 1] = 0;
                pixels[index + 2] = 0;
                if (distance >= 1.0) {
                    pixels[index + 3] = 0;
                    continue;
                }
                double falloff = 1.0 - distance * distance;
                pixels[index + 3] = (byte) Math.round(150.0 * falloff * falloff);
            }
        }
        return encodeRgba(s, s, pixels);
    }

    /** Writes an RGBA8 PNG (no filtering, one IDAT) over {@code width*height*4} bytes. */
    private static byte[] encodeRgba(int width, int height, byte[] pixels) {
        int stride = width * 4;
        byte[] raw = new byte[(stride + 1) * height];
        for (int y = 0; y < height; y++) {
            int row = y * (stride + 1);
            raw[row] = 0;
            System.arraycopy(pixels, y * stride, raw, row + 1, stride);
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
            writeInt(ihdr, 0, width);
            writeInt(ihdr, 4, height);
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
