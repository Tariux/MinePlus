package com.mineplus.infrastructure.mob.group;

/**
 * How a crew arranges itself around its formation point.
 *
 * <p>A formation is an <b>offset</b> per member, expressed in the crew's own
 * frame: {@code forward} is along the direction of travel and {@code lateral}
 * is to its right. {@link SquadManager} turns that pair into a world-space slot,
 * so every scenario shares one piece of arithmetic instead of each one
 * improvising "stand two blocks to my left".
 *
 * <p>Index 0 is the crew's leader (see {@link SquadManager#slot}). Formations
 * that exist to charge or encircle a target ({@link #RUSH}, {@link #PINCER},
 * {@link #SURROUND}) are applied around the formation point itself, which is
 * why a raid crew sets its rally on the village rather than on a waypoint.
 */
public enum Formation {

    /** No offset: every member walks straight to the formation point. */
    NONE,

    /** One behind the other — narrow, good for doorways and caves. */
    COLUMN,

    /** Shoulder to shoulder across the direction of travel. */
    LINE,

    /** A V with the leader at its point: broad front, everyone sees the target. */
    WEDGE,

    /** A loose charge: members spread slightly but all press toward the goal. */
    RUSH,

    /** Two wings that approach the formation point from opposite sides. */
    PINCER,

    /** A ring around the formation point — encirclement. */
    SURROUND;

    /**
     * The offset for one member, in blocks.
     *
     * @param index   position in the crew, 0 = leader
     * @param members crew size, used by {@link #LINE} and {@link #SURROUND}
     * @param spacing distance between adjacent slots
     * @return {@code {forward, lateral}} in blocks
     */
    public double[] slot(int index, int members, double spacing) {
        double s = Math.max(0.5, spacing);
        int count = Math.max(1, members);
        int position = Math.max(0, index);
        switch (this) {
            case COLUMN:
                return new double[]{-position * s, 0.0};
            case LINE:
                return new double[]{0.0, (position - (count - 1) / 2.0) * s};
            case WEDGE: {
                if (position == 0) {
                    return new double[]{0.0, 0.0};
                }
                int row = (position + 1) / 2;
                int side = position % 2 == 1 ? -1 : 1;
                return new double[]{-row * s * 0.8, side * row * s * 0.6};
            }
            case RUSH: {
                if (position == 0) {
                    return new double[]{0.0, 0.0};
                }
                int row = (position + 1) / 2;
                int side = position % 2 == 1 ? -1 : 1;
                return new double[]{-Math.min(row, 2) * s * 0.4, side * row * s * 0.35};
            }
            case PINCER: {
                if (position == 0) {
                    return new double[]{0.0, 0.0};
                }
                int side = position % 2 == 1 ? -1 : 1;
                int wing = (position + 1) / 2;
                return new double[]{0.0, side * wing * s};
            }
            case SURROUND: {
                double angle = (2.0 * Math.PI * position) / Math.max(3, count);
                double radius = s * 2.5;
                return new double[]{Math.cos(angle) * radius, Math.sin(angle) * radius};
            }
            case NONE:
            default:
                return new double[]{0.0, 0.0};
        }
    }
}
