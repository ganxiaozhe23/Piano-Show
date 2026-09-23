package com.example.pianoshow;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Immutable, server-authoritative samples for one flying payload. */
public record MotionPath(Vec3d start, Vec3d target, long durationTicks, List<Vec3d> samples) {
    public MotionPath {
        if (start == null || target == null) throw new IllegalArgumentException("motion endpoints cannot be null");
        durationTicks = Math.max(1, durationTicks);
        List<Vec3d> copy = new ArrayList<>(samples == null ? List.of() : samples);
        if (copy.size() != durationTicks + 1) {
            copy.clear();
            for (int i = 0; i <= durationTicks; i++) {
                double t = i / (double) durationTicks;
                copy.add(start.lerp(target, t));
            }
        }
        copy.set(0, start);
        copy.set(copy.size() - 1, target);
        samples = List.copyOf(copy);
    }

    public Vec3d positionAt(long elapsed) {
        if (elapsed <= 0) return start;
        if (elapsed >= durationTicks) return target;
        return samples.get((int) elapsed);
    }

    /** Returns at most maxSegments + 1 poses including exact endpoints. */
    public List<Vec3d> keyframes(int maxSegments) {
        int segments = Math.max(1, Math.min(maxSegments, (int) durationTicks));
        List<Vec3d> result = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            long tick = Math.round(i * (double) durationTicks / segments);
            result.add(positionAt(tick));
        }
        result.set(0, start);
        result.set(result.size() - 1, target);
        return List.copyOf(result);
    }
}
