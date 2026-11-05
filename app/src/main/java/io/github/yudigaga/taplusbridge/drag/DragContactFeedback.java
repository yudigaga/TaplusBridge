package io.github.yudigaga.taplusbridge.drag;

import java.util.Objects;

/** A new contact pulses once; a held contact never repeats. */
public final class DragContactFeedback {
    private String previous;
    public boolean update(String touching) {
        boolean pulse = touching != null && !Objects.equals(previous, touching);
        previous = touching;
        return pulse;
    }
}
