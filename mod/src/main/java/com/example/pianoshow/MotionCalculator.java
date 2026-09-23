package com.example.pianoshow;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Deterministic surface-aware motion generation shared by Display and physical modes. */
public final class MotionCalculator {
    private MotionCalculator() {}

    public static MotionPath arc(PianoLayout layout, Vec3d start, Vec3d target, long durationTicks,
                                 double arcHeight, double scatterRadius, long seed) {
        long duration = Math.max(1, durationTicks);
        double height = Double.isFinite(arcHeight) ? Math.max(0, arcHeight) : 0;
        double radius = Math.max(0, scatterRadius);
        long[] randomState = {seed};
        double phase = nextUnit(randomState) * Math.PI * 2;
        double amplitude = radius * (0.35 + nextUnit(randomState) * 0.65);
        // Decorative scatter is constrained to the horizontal/tangent axis of
        // the canvas.  In particular, do not use the image-down axis here:
        // that axis is vertical on wall surfaces and would make payloads
        // wobble up/down independently of the configured arc height.
        Vec3d tangent = layout.canvasAxisX();
        List<Vec3d> samples = new ArrayList<>((int) duration + 1);
        for (int i = 0; i <= duration; i++) {
            double t = i / (double) duration;
            Vec3d position = start.lerp(target, t);
            double envelope = Math.sin(Math.PI * t);
            double arc = Math.sin(Math.PI * t) * height;
            double scatter = Math.sin(phase + t * Math.PI * 2) * amplitude * envelope;
            double scatter2 = Math.cos(phase + t * Math.PI * 2) * amplitude * 0.35 * envelope;
            position = position.add(0, arc, 0)
                    .add(tangent.multiply(scatter + scatter2));
            samples.add(position);
        }
        return new MotionPath(start, target, duration, samples);
    }

    public static MotionPath ballistic(PianoLayout layout, Vec3d start, Vec3d target, long durationTicks,
                                       double gravity, double drag, double scatterRadius, long seed) {
        long duration = Math.max(1, durationTicks);
        double g = Math.max(0, Math.min(1, gravity));
        double d = Math.max(0, Math.min(1, drag));
        double sum = geometricSum(d, duration);
        Vec3d acceleration = new Vec3d(0, -g, 0);
        Vec3d gravityDisplacement = Vec3d.ZERO;
        Vec3d velocity = Vec3d.ZERO;
        for (int i = 0; i < duration; i++) {
            gravityDisplacement = gravityDisplacement.add(velocity);
            velocity = velocity.multiply(d).add(acceleration);
        }
        Vec3d initial = target.subtract(start).subtract(gravityDisplacement).multiply(1.0 / sum);
        long[] randomState = {seed};
        double phase = nextUnit(randomState) * Math.PI * 2;
        double amplitude = Math.max(0, scatterRadius) * (0.35 + nextUnit(randomState) * 0.65);
        Vec3d tangent = layout.canvasAxisX();
        List<Vec3d> samples = new ArrayList<>((int) duration + 1);
        Vec3d position = start;
        velocity = initial;
        samples.add(start);
        for (int i = 1; i <= duration; i++) {
            position = position.add(velocity);
            velocity = velocity.multiply(d).add(acceleration);
            double t = i / (double) duration;
            double envelope = Math.sin(Math.PI * t);
            double scatter = Math.sin(phase + t * Math.PI * 2) * amplitude * envelope;
            double scatter2 = Math.cos(phase + t * Math.PI * 2) * amplitude * 0.35 * envelope;
            Vec3d sample = position.add(tangent.multiply(scatter + scatter2));
            samples.add(i == duration ? target : sample);
        }
        return new MotionPath(start, target, duration, samples);
    }

    /**
     * Solves the initial velocity for Minecraft's discrete falling-block
     * integrator.  FallingBlockEntity advances by the current velocity and
     * then applies drag/gravity for the next tick, so the returned vector can
     * be assigned once at spawn and left entirely to vanilla physics.
     */
    public static Vec3d vanillaVelocity(Vec3d start, Vec3d target, long durationTicks,
                                        double gravity, double drag) {
        if (start == null || target == null) throw new IllegalArgumentException("motion endpoints cannot be null");
        long duration = Math.max(1, durationTicks);
        double g = clamp(gravity, 0.0, 1.0);
        double d = clamp(drag, 0.0, 1.0);
        Vec3d gravityDisplacement = Vec3d.ZERO;
        Vec3d velocity = Vec3d.ZERO;
        for (int i = 0; i < duration; i++) {
            gravityDisplacement = gravityDisplacement.add(velocity);
            velocity = velocity.multiply(d).add(0, -g, 0);
        }
        double sum = geometricSum(d, duration);
        return target.subtract(start).subtract(gravityDisplacement).multiply(1.0 / sum);
    }

    /** Layout-aware overload matching the other motion calculators. */
    public static Vec3d vanillaVelocity(PianoLayout layout, Vec3d start, Vec3d target, long durationTicks,
                                        double gravity, double drag) {
        return vanillaVelocity(start, target, durationTicks, gravity, drag);
    }

    /** Predicted path using the same discrete integrator as vanillaVelocity. */
    public static MotionPath vanillaPath(PianoLayout layout, Vec3d start, Vec3d target, long durationTicks,
                                         double gravity, double drag, double scatterRadius, long seed) {
        // Native Motion receives one initial velocity and cannot reproduce the
        // per-sample decorative scatter used by ballistic previews. Keep the
        // prediction collision-free and deterministic around the same gravity
        // curve; the server still commits the exact target after collision.
        return ballistic(layout, start, target, durationTicks, gravity, drag, 0.0, seed);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double geometricSum(double drag, long duration) {
        if (drag == 1.0) return duration;
        return (1.0 - Math.pow(drag, duration)) / (1.0 - drag);
    }

    /** Matches the lightweight LCG used by the standalone Web preview. */
    private static double nextUnit(long[] state) {
        state[0] = (1664525L * (state[0] & 0xffffffffL) + 1013904223L) & 0xffffffffL;
        return (state[0] & 0xffffffffL) / 4294967296.0;
    }
}
