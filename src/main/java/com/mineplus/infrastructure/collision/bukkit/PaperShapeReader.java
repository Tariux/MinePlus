package com.mineplus.infrastructure.collision.bukkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

/**
 * Reads shapes through Paper's contextual {@code BlockData#getCollisionShape(Location)}.
 *
 * <p>The method is invoked reflectively because the project compiles against the
 * Spigot API: a compile-time reference would make the plugin fail to link on
 * Spigot. Only the public Bukkit/Paper API is touched, never NMS. The location
 * is a hint for context-dependent states and does not have to be loaded.</p>
 */
public final class PaperShapeReader implements ShapeReader {

    private final Method shapeMethod;
    private final Location context;

    private PaperShapeReader(Method shapeMethod, Location context) {
        this.shapeMethod = shapeMethod;
        this.context = context;
    }

    /** Returns a reader when the server has the API, otherwise null. */
    public static PaperShapeReader create(Location context) {
        try {
            Method method = BlockData.class.getMethod("getCollisionShape", Location.class);
            return new PaperShapeReader(method, context == null ? new Location(null, 0, 0, 0) : context);
        } catch (Throwable missing) {
            return null;
        }
    }

    @Override
    public ShapeSample read(BlockData data) {
        try {
            Object result = shapeMethod.invoke(data, context);
            if (!(result instanceof VoxelShape shape)) {
                return new ShapeSample(List.of(), false);
            }
            Collection<BoundingBox> boxes = shape.getBoundingBoxes();
            List<double[]> converted = new ArrayList<>(boxes.size());
            for (BoundingBox box : boxes) {
                converted.add(new double[] {
                        clamp(box.getMinX()), clamp(box.getMinY()), clamp(box.getMinZ()),
                        clamp(box.getMaxX()), clamp(box.getMaxY()), clamp(box.getMaxZ())});
            }
            return new ShapeSample(converted, !converted.isEmpty());
        } catch (Throwable failure) {
            return new ShapeSample(List.of(), false);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public boolean worldFree() {
        return true;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
