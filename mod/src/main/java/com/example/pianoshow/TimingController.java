package com.example.pianoshow;

/** Bounded, deterministic visual budget controller for MIDI-driven shows. */
public final class TimingController {
    public record Budget(int spawnPerTick, long flightTicks, double density, int backlog) {}

    private TimingController() {}

    public static Budget budget(ShowPackage show, long now, int nextEvent, int pending, int active) {
        int windowTicks = 40;
        int densityEvents = 0;
        for (int i = Math.max(0, nextEvent); i < show.events().size(); i++) {
            long tick = show.events().get(i).tick();
            if (tick >= now + windowTicks) break;
            if (tick >= now) densityEvents++;
        }
        double density = densityEvents / (double) windowTicks;
        int backlog = Math.max(0, pending) + Math.max(0, active);
        if ("fixed".equals(show.timingMode())) {
            return new Budget(Math.max(1, show.maxSpawnPerTick()), show.flightTicksForVelocity(96), density, backlog);
        }
        int backlogRatio = Math.max(1, show.maxActiveDisplays());
        int spawn = show.baseSpawnPerTick()
                + (int) Math.ceil(density * 8.0)
                + Math.min(32, pending / 32);
        spawn = Math.max(8, Math.min(show.maxSpawnPerTick(), spawn));
        long flight = show.targetLeadTicks()
                - (long) Math.ceil(density * 2.0)
                - (long) Math.floor((pending / (double) backlogRatio) * 12.0);
        flight = Math.max(show.flightTicksMin(), Math.min(show.flightTicksMax(), flight));
        return new Budget(spawn, flight, density, backlog);
    }
}
