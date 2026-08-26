package com.example.pianoshow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Versioned reader for the editor's .pshow ZIP format. */
public final class ShowPackage {
    public record PaletteEntry(Identifier block, int red, int green, int blue, String name) {}

    private final JsonObject manifest;
    private final JsonObject layout;
    private final List<NoteEvent> events;
    private final List<Pixel> pixels;
    private final List<PaletteEntry> palette;

    private ShowPackage(JsonObject manifest, JsonObject layout, List<NoteEvent> events, List<Pixel> pixels, List<PaletteEntry> palette) {
        this.manifest = manifest;
        this.layout = layout;
        this.events = List.copyOf(events);
        this.pixels = List.copyOf(pixels);
        this.palette = List.copyOf(palette);
    }

    public static ShowPackage load(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var manifestEntry = zip.getEntry("manifest.json");
            var eventsEntry = zip.getEntry("events.bin");
            var pixelsEntry = zip.getEntry("pixels.bin");
            var paletteEntry = zip.getEntry("palette.json");
            if (manifestEntry == null || eventsEntry == null || pixelsEntry == null || paletteEntry == null) {
                throw new IOException("Invalid .pshow package: required entry missing");
            }
            JsonObject manifest = JsonParser.parseString(new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var layoutEntry = zip.getEntry("layout.json");
            JsonObject layout = layoutEntry == null
                    ? new JsonObject()
                    : JsonParser.parseString(new String(zip.getInputStream(layoutEntry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            int formatVersion = manifest.has("formatVersion") ? manifest.get("formatVersion").getAsInt() : 1;
            if (formatVersion < 1 || formatVersion > 2) throw new IOException("Unsupported .pshow format version: " + formatVersion);
            List<NoteEvent> events = decodeEvents(zip.getInputStream(eventsEntry).readAllBytes());
            List<Pixel> pixels = decodePixels(zip.getInputStream(pixelsEntry).readAllBytes());
            List<PaletteEntry> palette = decodePalette(zip.getInputStream(paletteEntry).readAllBytes());
            return new ShowPackage(manifest, layout, events, pixels, palette);
        }
    }

    private static List<NoteEvent> decodeEvents(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)))) {
            byte[] magic = input.readNBytes(8);
            if (!"PSHOWEV1".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("Invalid events.bin magic");
            int count = input.readInt();
            if (count < 0 || count > 10_000_000) throw new IOException("Invalid event count");
            List<NoteEvent> events = new ArrayList<>(count);
            long tick = 0;
            for (int i = 0; i < count; i++) {
                tick += readVarint(input);
                int note = input.readUnsignedByte();
                int velocity = input.readUnsignedByte();
                long duration = readVarint(input);
                int track = (int) readVarint(input);
                int channel = input.readUnsignedByte();
                events.add(new NoteEvent(tick, note, velocity, duration, track, channel));
            }
            return events;
        }
    }

    private static List<Pixel> decodePixels(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = input.readNBytes(8);
            if (!"PSHOWPX1".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("Invalid pixels.bin magic");
            int count = input.readInt();
            if (count < 0 || count > 100_000_000) throw new IOException("Invalid pixel count");
            List<Pixel> pixels = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                pixels.add(new Pixel(input.readUnsignedShort(), input.readUnsignedShort(), input.readUnsignedShort(), input.readInt()));
            }
            return pixels;
        }
    }

    private static List<PaletteEntry> decodePalette(byte[] bytes) {
        JsonArray array = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
        List<PaletteEntry> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            JsonArray color = item.getAsJsonArray("color");
            result.add(new PaletteEntry(
                    Identifier.of(item.get("block").getAsString()),
                    color.get(0).getAsInt(), color.get(1).getAsInt(), color.get(2).getAsInt(),
                    item.has("name") ? item.get("name").getAsString() : ""));
        }
        return result;
    }

    private static long readVarint(DataInputStream input) throws IOException {
        long value = 0;
        int shift = 0;
        while (shift < 63) {
            int next = input.readUnsignedByte();
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IOException("Malformed varint");
    }

    public JsonObject manifest() { return manifest; }
    public JsonObject layout() { return layout; }
    public List<NoteEvent> events() { return events; }
    public List<Pixel> pixels() { return pixels; }
    public List<PaletteEntry> palette() { return palette; }
    public int imageWidth() { return manifest.get("imageWidth").getAsInt(); }
    public int imageHeight() { return manifest.get("imageHeight").getAsInt(); }
    public int formatVersion() { return manifest.has("formatVersion") ? manifest.get("formatVersion").getAsInt() : 1; }
    public int pixelScale() {
        JsonObject canvas = layout.has("canvas") && layout.get("canvas").isJsonObject()
                ? layout.getAsJsonObject("canvas") : new JsonObject();
        if (canvas.has("pixelScale")) return Math.max(1, Math.min(3, canvas.get("pixelScale").getAsInt()));
        return manifest.has("pixelScale") ? Math.max(1, Math.min(3, manifest.get("pixelScale").getAsInt())) : 1;
    }
    public int physicalWidth() { return manifest.has("physicalWidth") ? manifest.get("physicalWidth").getAsInt() : imageWidth() * pixelScale(); }
    public int physicalHeight() { return manifest.has("physicalHeight") ? manifest.get("physicalHeight").getAsInt() : imageHeight() * pixelScale(); }
    public String visualMode() { return manifest.has("visualMode") ? manifest.get("visualMode").getAsString() : "physical"; }
    public String timingMode() { return manifest.has("timingMode") ? manifest.get("timingMode").getAsString() : "fixed"; }
    private JsonObject effectLimits() {
        return manifest.has("effectLimits") && manifest.get("effectLimits").isJsonObject()
                ? manifest.getAsJsonObject("effectLimits") : new JsonObject();
    }

    public int maxSpawnPerTick() { return intLimit("maxSpawnPerTick", formatVersion() >= 2 ? 64 : 24); }
    public int maxCommitPerTick() { return intLimit("maxCommitPerTick", formatVersion() >= 2 ? 512 : 128); }
    public int maxActiveDisplays() { return intLimit("maxActiveDisplays", formatVersion() >= 2 ? 512 : 2048); }
    public int maxActiveFallingBlocks() {
        JsonObject limits = effectLimits();
        return limits.has("maxActiveFallingBlocks")
                ? Math.max(1, limits.get("maxActiveFallingBlocks").getAsInt())
                : maxActiveDisplays();
    }

    public int baseSpawnPerTick() { return intLimit("baseSpawnPerTick", formatVersion() >= 2 ? 32 : maxSpawnPerTick()); }
    public int targetLeadTicks() { return intLimit("targetLeadTicks", formatVersion() >= 2 ? 28 : 40); }

    private int intLimit(String name, int fallback) {
        JsonObject limits = effectLimits();
        return limits.has(name) ? Math.max(1, limits.get(name).getAsInt()) : fallback;
    }

    public int maxSparkBurstMin() {
        JsonObject limits = effectLimits();
        return limits.has("sparkBurstMin") ? limits.get("sparkBurstMin").getAsInt() : 4;
    }
    public int maxSparkBurstMax() {
        JsonObject limits = effectLimits();
        return limits.has("sparkBurstMax") ? limits.get("sparkBurstMax").getAsInt() : 12;
    }

    public int flightTicksMin() {
        return intLimit("flightTicksMin", formatVersion() >= 2 ? 12 : 30);
    }

    public int flightTicksMax() {
        return Math.max(flightTicksMin(), intLimit("flightTicksMax", formatVersion() >= 2 ? 36 : 50));
    }

    public int snapTicks() {
        return Math.max(1, Math.min(flightTicksMin(), intLimit("snapTicks", 5)));
    }

    public double scatterRadius() {
        JsonObject limits = effectLimits();
        return limits.has("scatterRadius") ? Math.max(0.0, limits.get("scatterRadius").getAsDouble()) : 2.5;
    }

    public long randomSeed() {
        return manifest.has("randomSeed") ? manifest.get("randomSeed").getAsLong() : 0L;
    }

    public long flightTicks(long ticks) {
        return Math.max(flightTicksMin(), Math.min(flightTicksMax(), ticks));
    }

    public long flightTicksForVelocity(int velocity) {
        double normalized = Math.max(0, Math.min(127, velocity)) / 127.0;
        long ticks = Math.round(flightTicksMax() - normalized * (flightTicksMax() - flightTicksMin()));
        return flightTicks(ticks);
    }
}
