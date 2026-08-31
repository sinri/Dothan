package io.github.sinri.Dothan.Config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the single atomically published configuration generation.
 */
public final class DothanConfigManager {
    private final AtomicReference<DothanConfigSnapshot> active = new AtomicReference<>();

    public DothanConfigSnapshot current() {
        return active.get();
    }

    public boolean initialize(DothanConfigSnapshot snapshot) {
        return active.compareAndSet(null, Objects.requireNonNull(snapshot));
    }

    public boolean publish(DothanConfigSnapshot expected, DothanConfigSnapshot candidate) {
        Objects.requireNonNull(candidate);
        if (expected == null || candidate.getVersion() <= expected.getVersion()) {
            return false;
        }
        return active.compareAndSet(expected, candidate);
    }
}
