package com.mineplus.gun.runtime;

import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** One simulated round: a small step per tick until it hits, expires or leaves range. */
public final class Bullet {

    private final UUID shooterId;
    private final WeaponDefinition weapon;
    private final World world;
    private final Location position;
    private Vector velocity;
    private double travelled;
    private int penetrationLeft;
    private int ricochetLeft;
    private double damageMultiplier = 1.0;
    private boolean alive = true;

    public Bullet(UUID shooterId, WeaponDefinition weapon, World world, Location start, Vector velocity) {
        this.shooterId = shooterId;
        this.weapon = weapon;
        this.world = world;
        this.position = start.clone();
        this.velocity = velocity.clone();
        this.penetrationLeft = weapon.penetration();
        this.ricochetLeft = weapon.ricochet() > 0 ? 1 + (int) Math.round(weapon.ricochet()) : 0;
    }

    public UUID shooterId() { return shooterId; }
    public WeaponDefinition weapon() { return weapon; }
    public World world() { return world; }
    public Location position() { return position; }
    public void position(Location value) {
        this.position.setX(value.getX());
        this.position.setY(value.getY());
        this.position.setZ(value.getZ());
        if (value.getWorld() != null) {
            this.position.setWorld(value.getWorld());
        }
    }
    public Vector velocity() { return velocity; }
    public void velocity(Vector value) { this.velocity = value; }
    public double travelled() { return travelled; }
    public void addTravelled(double distance) { this.travelled += distance; }
    public int penetrationLeft() { return penetrationLeft; }
    public void consumePenetration() { this.penetrationLeft--; }
    public int ricochetLeft() { return ricochetLeft; }
    public void consumeRicochet() { this.ricochetLeft--; }
    public double damageMultiplier() { return damageMultiplier; }
    public void multiplyDamage(double factor) { this.damageMultiplier *= factor; }
    public boolean alive() { return alive; }
    public void kill() { this.alive = false; }
}
