from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import mido

from .models import NoteEvent


@dataclass(frozen=True)
class _Message:
    tick: int
    track: int
    order: int
    message: mido.Message | mido.MetaMessage


def _tempo_at_tick(tempo_changes: list[tuple[int, int]], tick: int, ticks_per_beat: int) -> float:
    """Return elapsed seconds at a MIDI tick using the tempo map."""
    seconds = 0.0
    previous_tick = 0
    tempo = 500_000
    for change_tick, next_tempo in tempo_changes:
        if change_tick > tick:
            break
        seconds += (change_tick - previous_tick) * tempo / 1_000_000 / ticks_per_beat
        previous_tick = change_tick
        tempo = next_tempo
    seconds += (tick - previous_tick) * tempo / 1_000_000 / ticks_per_beat
    return seconds


def read_midi(path: str | Path, *, tempo_scale: float = 1.0, include_percussion: bool = False) -> list[NoteEvent]:
    """Read note-on events and convert their timing to Minecraft server ticks."""
    if tempo_scale <= 0:
        raise ValueError("tempo_scale must be greater than zero")
    midi = mido.MidiFile(str(path))
    messages: list[_Message] = []
    tempo_changes: list[tuple[int, int]] = [(0, 500_000)]
    for track_index, track in enumerate(midi.tracks):
        absolute = 0
        for order, message in enumerate(track):
            absolute += message.time
            messages.append(_Message(absolute, track_index, order, message))
            if message.type == "set_tempo":
                tempo_changes.append((absolute, int(message.tempo)))
    tempo_changes.sort(key=lambda item: item[0])

    # Last tempo change at a tick wins, which matches MIDI semantics.
    compact_tempo: list[tuple[int, int]] = []
    for tick, tempo in tempo_changes:
        if compact_tempo and compact_tempo[-1][0] == tick:
            compact_tempo[-1] = (tick, tempo)
        else:
            compact_tempo.append((tick, tempo))

    messages.sort(key=lambda item: (item.tick, item.track, item.order))
    active: defaultdict[tuple[int, int], list[tuple[int, int, int, int]]] = defaultdict(list)
    result: list[NoteEvent] = []
    output_order = 0
    for item in messages:
        message = item.message
        if message.type == "note_on" and message.velocity > 0:
            if not include_percussion and message.channel == 9:
                continue
            active[(message.channel, message.note)].append(
                (item.tick, int(message.velocity), item.track, item.order)
            )
            continue
        if message.type not in {"note_off", "note_on"}:
            continue
        if message.type == "note_on" and message.velocity != 0:
            continue
        key = (message.channel, message.note)
        if not active[key]:
            continue
        start_tick, velocity, track, original_order = active[key].pop(0)
        start_seconds = _tempo_at_tick(compact_tempo, start_tick, midi.ticks_per_beat)
        end_seconds = _tempo_at_tick(compact_tempo, item.tick, midi.ticks_per_beat)
        start_server_tick = round(start_seconds / tempo_scale * 20)
        end_server_tick = round(end_seconds / tempo_scale * 20)
        result.append(
            NoteEvent(
                tick=max(0, start_server_tick),
                note=int(message.note),
                velocity=max(1, min(127, velocity)),
                duration_ticks=max(1, end_server_tick - start_server_tick),
                track=track,
                channel=int(message.channel),
                order=output_order,
            )
        )
        output_order += 1
    result.sort(key=lambda event: (event.tick, event.track, event.channel, event.order))
    return result


def event_summary(events: Iterable[NoteEvent]) -> dict[str, int]:
    events = list(events)
    return {
        "events": len(events),
        "first_tick": events[0].tick if events else 0,
        "last_tick": max((event.tick for event in events), default=0),
        "max_note": max((event.note for event in events), default=0),
        "min_note": min((event.note for event in events), default=0),
    }
