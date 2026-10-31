package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.fx.Sounds;
import org.bukkit.Sound;

/**
 * A sound cue that fires once when its unit starts. Routes through
 * {@link Sounds}, so an unresolvable custom event degrades to the fallback
 * instead of throwing on the tick thread.
 */
public final class SoundCue implements VisualComponent {

    private final String event;
    private final float volume;
    private final float pitch;
    private final Sound fallback;
    private final int priority;

    public SoundCue(String event, float volume, float pitch, Sound fallback) {
        this(event, volume, pitch, fallback, 0);
    }

    public SoundCue(String event, float volume, float pitch, Sound fallback, int priority) {
        this.event = event;
        this.volume = volume;
        this.pitch = pitch;
        this.fallback = fallback;
        this.priority = priority;
    }

    @Override
    public void start(VisualUnitService service) {
        if (event == null || event.isBlank()) {
            return;
        }
        Sounds.play(service.anchorOfCurrent(), event, volume, pitch, fallback);
    }

    @Override
    public int priority() {
        return priority;
    }
}
