package com.mineplus.infrastructure.mob.custom;

/**
 * Explicit per-face texture windows of one {@link MobBox}, in texture pixels.
 *
 * <p>Vanilla entity models derive every face window from the box-UV cross
 * layout encoded by {@link MobBox#textureU()}/{@link MobBox#textureV()}.
 * Models authored with per-face UVs (Blockbench with box UV off, Bedrock
 * projects) carry the windows directly instead; those are copied verbatim, so
 * a face that was authored to mirror stays mirrored — the same {@code u2 < u1}
 * or {@code v2 < v1} convention vanilla model JSON uses.</p>
 */
public record MobFaceUvs(Uv north, Uv south, Uv east, Uv west, Uv up, Uv down) {

    /** One face window in texture pixels. */
    public record Uv(float u1, float v1, float u2, float v2) {
    }

    /** The window for a vanilla face key ({@code north}, {@code up}, …), or {@code null}. */
    public Uv forDirection(String direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case "north" -> north;
            case "south" -> south;
            case "east" -> east;
            case "west" -> west;
            case "up" -> up;
            case "down" -> down;
            default -> null;
        };
    }
}
