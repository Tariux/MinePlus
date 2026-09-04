package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.VirtualModel;

/**
 * Shared per-face texture sampler: the single bridge between a face's UV window
 * and the decoded PNG behind it. Both texel surface baking
 * ({@link TexelSurfaceBaker}) and the occupancy grid calculator sample through this class,
 * so the two paths can never disagree about window mapping, in-plane UV rotation,
 * clamping, or PNG resolution scaling.
 * <p>Coordinates: {@code (fu, fv)} are display fractions over the face —
 * {@code fu = 0..1} along the face's U axis, {@code fv = 0..1} from the <i>top</i>
 * of the UV window down (matching how plates and texel grids position themselves:
 * texture-window row 0 sits at local v-axis 1). The sample is taken at
 * {@code u = u1 + fu·(u2−u1)}, {@code v = v1 + fv·(v2−v1)} in resolution-space
 * texture pixels, rotated by −θ about the window center for in-plane UV rotation
 * (90° steps are exact integer remaps), clamped into the window, and finally
 * mapped to PNG pixels through the resolution scale. Reversed windows
 * ({@code u1 > u2} or {@code v1 > v2}) sample mirrored, exactly as vanilla
 * interprets flipped UV coordinates — the texel reconstruction is therefore
 * faithful to models authored with flipped windows.
 *
 * <p>Implementation: the source PNG is consumed as a bulk-extracted
 * {@link TextureImageStore.TextureRaster} ARGB array, and the window-to-rotation
 * affine basis is fully precomputed in the constructor — {@link #sample} is pure
 * primitive arithmetic over the raster (no allocation, no per-call color-model
 * dispatch), because bakers call it once per texel per supersample.
 *
 * <p>Transparency is cutout-style: a sample with alpha &lt; 128 returns {@code 0}
 * (the sentinel callers treat as "no contribution").
 */
public final class TexelSampler {

    private final BakedFace face;
    private final int[] pixels;
    private final int width;
    private final int height;
    private final float pngScaleX;
    private final float pngScaleY;
    private final int rotationSteps;
    private final float uMin;
    private final float uMax;
    private final float vMin;
    private final float vMax;

    /** Fully precomputed window+rotation affine: {@code u = uC + uA·fu + uB·fv}. */
    private final float uC;
    private final float uA;
    private final float uB;
    private final float vC;
    private final float vA;
    private final float vB;

    /**
     * @param face       the face whose UV window is sampled
     * @param raster     the bulk ARGB raster for the face's texture (never {@code null})
     * @param resolution the model's texture resolution (bbmodel {@code resolution})
     */
    public TexelSampler(BakedFace face, TextureImageStore.TextureRaster raster, VirtualModel.Resolution resolution) {
        this.face = face;
        this.pixels = raster.argb();
        this.width = raster.width();
        this.height = raster.height();
        this.pngScaleX = width / (float) resolution.width();
        this.pngScaleY = height / (float) resolution.height();
        this.rotationSteps = Math.round(face.rotation() / 90.0f) % 4;
        this.uMin = Math.min(face.u1(), face.u2());
        this.uMax = Math.max(face.u1(), face.u2());
        this.vMin = Math.min(face.v1(), face.v2());
        this.vMax = Math.max(face.v1(), face.v2());

        // Window-center-relative sample basis before in-plane rotation:
        // u0 = u1 + fu·(u2-u1), v0 = v1 + fv·(v2-v1). The rotation by -theta
        // about the window center is a fixed linear remap of the center-relative
        // (du, dv), so the whole window+rotation affine collapses into six
        // coefficients computed here.
        float uCenter = (face.u1() + face.u2()) * 0.5f;
        float vCenter = (face.v1() + face.v2()) * 0.5f;
        float du0 = face.u1() - uCenter;
        float duW = face.u2() - face.u1();
        float dv0 = face.v1() - vCenter;
        float dvW = face.v2() - face.v1();
        switch (rotationSteps) {
            // su = dv, sv = -du
            case 1 -> {
                uC = uCenter + dv0;
                uA = 0.0f;
                uB = dvW;
                vC = vCenter - du0;
                vA = -duW;
                vB = 0.0f;
            }
            // su = -du, sv = -dv
            case 2 -> {
                uC = uCenter - du0;
                uA = -duW;
                uB = 0.0f;
                vC = vCenter - dv0;
                vA = 0.0f;
                vB = -dvW;
            }
            // su = -dv, sv = du
            case 3 -> {
                uC = uCenter - dv0;
                uA = 0.0f;
                uB = -dvW;
                vC = vCenter + du0;
                vA = duW;
                vB = 0.0f;
            }
            default -> {
                uC = uCenter + du0;
                uA = duW;
                uB = 0.0f;
                vC = vCenter + dv0;
                vA = 0.0f;
                vB = dvW;
            }
        }
    }

    /** The face this sampler reads. */
    public BakedFace face() {
        return face;
    }

    /**
     * Point sample at display fraction {@code (fu, fv)}.
     *
     * @return the PNG pixel's ARGB, or {@code 0} when the sampled texel is
     *         transparent (alpha &lt; 128) — the cutout sentinel
     */
    public int sample(float fu, float fv) {
        float u = uC + uA * fu + uB * fv;
        float v = vC + vA * fu + vB * fv;

        u = clamp(u, uMin, uMax);
        v = clamp(v, vMin, vMax);
        int px = (int) (u * pngScaleX);
        int py = (int) (v * pngScaleY);
        if (px < 0) {
            px = 0;
        } else if (px > width - 1) {
            px = width - 1;
        }
        if (py < 0) {
            py = 0;
        } else if (py > height - 1) {
            py = height - 1;
        }

        int argb = pixels[py * width + px];
        return ((argb >>> 24) & 0xFF) >= 128 ? argb : 0;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
