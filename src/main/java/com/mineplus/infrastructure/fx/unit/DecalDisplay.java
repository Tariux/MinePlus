package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.joml.Matrix4f;

/**
 * A small surface mark (a scorch, a hole) rendered as one pooled
 * {@code BlockDisplay} through the display transport.
 *
 * <p>The display is acquired from the transport's pool (the same
 * {@code EntityPoolManager} the virtual engine uses), attaches to in-range
 * viewers exactly like a rendered model, and is <b>always</b> returned: on
 * normal expiry, when the unit is dropped by the budget, and when the service
 * shuts down. Without a running transport the decal is simply omitted; a
 * shader-less server still gets the particle and sound parts of the unit.</p>
 */
public final class DecalDisplay implements VisualComponent {

    private final Material material;
    private final float scale;
    private final int priority;
    private UUID instanceId;

    public DecalDisplay(Material material, float scale) {
        this(material, scale, 0);
    }

    public DecalDisplay(Material material, float scale, int priority) {
        this.material = material;
        this.scale = Math.max(0.02f, Math.min(1.0f, scale));
        this.priority = priority;
    }

    @Override
    public void start(VisualUnitService service) {
        if (material == null || material.isAir() || instanceId != null) {
            return;
        }
        DisplayTransport transport = service.transport();
        Location at = service.anchorOfCurrent();
        if (transport == null || at == null || at.getWorld() == null) {
            return;
        }
        UUID id = UUID.randomUUID();
        try {
            PooledDisplay display = transport.beginInstance(id, at::getWorld);
            display.asBlockDisplay().setBlock(material.createBlockData());
            display.moveTo(transport.nms(), at.getX(), at.getY() + 0.01, at.getZ(), 0f, 0f);
            display.setTransform(new Matrix4f().translate(0f, 0.01f, 0f).scale(scale), 0);
            transport.finishInstance(id, at, false);
            instanceId = id;
            service.registerDecal(id);
        } catch (Throwable failure) {
            // A decorative mark must never break the impact that produced it.
            if (instanceId != null) {
                release(service);
            }
        }
    }

    @Override
    public boolean tick(VisualUnitService service) {
        // The decal lives exactly as long as its unit; the unit's lifetime
        // expires it and cancel() returns the display.
        return true;
    }

    @Override
    public void cancel(VisualUnitService service) {
        release(service);
    }

    private void release(VisualUnitService service) {
        UUID id = instanceId;
        if (id == null) {
            return;
        }
        instanceId = null;
        service.releaseDecal(id);
    }

    @Override
    public int priority() {
        return priority;
    }
}
