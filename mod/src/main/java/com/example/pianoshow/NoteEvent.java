package com.example.pianoshow;

public record NoteEvent(long tick, int note, int velocity, long durationTicks, int track, int channel) {
}
