package com.example.pianoshow;

import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Deterministic world layout for the keyboard and a scaled image canvas. */
public final class PianoLayout {
    public enum Surface { FLOOR, WALL_NORTH, WALL_SOUTH, WALL_EAST, WALL_WEST }

    private static final int[] BLACK_PITCH_CLASSES = {1, 3, 6, 8, 10};
    private final int noteMin;
    private final int noteMax;
    private final int keyboardDepth;
    private final int canvasGap;
    private final Surface surface;
    private final int imageWidth;
    private final int imageHeight;
    private final int pixelScale;
    private final int canvasLift;
    private final int canvasOffsetX;
    private final int canvasOffsetY;
    private final int canvasOffsetZ;
    private final int backingThickness;
    private final int borderThickness;

    public PianoLayout(int noteMin, int noteMax, int keyboardDepth, int canvasGap, Surface surface) {
        this(noteMin, noteMax, keyboardDepth, canvasGap, surface, 1, 1, 1, 1);
    }

    public PianoLayout(int noteMin, int noteMax, int keyboardDepth, int canvasGap, Surface surface,
                       int imageWidth, int imageHeight, int pixelScale, int canvasLift) {
        this(noteMin, noteMax, keyboardDepth, canvasGap, surface, imageWidth, imageHeight, pixelScale, canvasLift, 0, 0, 0);
    }

    public PianoLayout(int noteMin, int noteMax, int keyboardDepth, int canvasGap, Surface surface,
                       int imageWidth, int imageHeight, int pixelScale, int canvasLift,
                       int canvasOffsetX, int canvasOffsetY, int canvasOffsetZ) {
        this(noteMin, noteMax, keyboardDepth, canvasGap, surface, imageWidth, imageHeight, pixelScale, canvasLift,
                canvasOffsetX, canvasOffsetY, canvasOffsetZ, 1, 2);
    }

    public PianoLayout(int noteMin, int noteMax, int keyboardDepth, int canvasGap, Surface surface,
                       int imageWidth, int imageHeight, int pixelScale, int canvasLift,
                       int canvasOffsetX, int canvasOffsetY, int canvasOffsetZ,
                       int backingThickness, int borderThickness) {
        this.noteMin = noteMin;
        this.noteMax = noteMax;
        this.keyboardDepth = Math.max(1, keyboardDepth);
        this.canvasGap = Math.max(1, canvasGap);
        this.surface = surface;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        this.pixelScale = Math.max(1, Math.min(3, pixelScale));
        this.canvasLift = Math.max(0, canvasLift);
        this.canvasOffsetX = clampOffset(canvasOffsetX);
        this.canvasOffsetY = clampOffset(canvasOffsetY);
        this.canvasOffsetZ = clampOffset(canvasOffsetZ);
        this.backingThickness = Math.max(1, Math.min(16, backingThickness));
        this.borderThickness = Math.max(1, Math.min(16, borderThickness));
    }

    private static int clampOffset(int value) { return Math.max(-128, Math.min(128, value)); }

    public static PianoLayout from(ShowPackage show) {
        JsonObject layout = show.layout();
        JsonObject keyboard = layout.has("keyboard") && layout.get("keyboard").isJsonObject()
                ? layout.getAsJsonObject("keyboard") : new JsonObject();
        JsonObject canvas = layout.has("canvas") && layout.get("canvas").isJsonObject()
                ? layout.getAsJsonObject("canvas") : new JsonObject();
        int noteMin = keyboard.has("noteMin") ? keyboard.get("noteMin").getAsInt() : show.manifest().get("noteMin").getAsInt();
        int noteMax = keyboard.has("noteMax") ? keyboard.get("noteMax").getAsInt() : show.manifest().get("noteMax").getAsInt();
        int keyboardDepth = keyboard.has("depth") ? keyboard.get("depth").getAsInt() : 4;
        int canvasGap = 8;
        if (canvas.has("offset") && canvas.get("offset").isJsonArray()) {
            var offset = canvas.getAsJsonArray("offset");
            if (offset.size() >= 3) canvasGap = offset.get(2).getAsInt();
        }
        String surfaceName = canvas.has("surface") ? canvas.get("surface").getAsString()
                : layout.has("surface") ? layout.get("surface").getAsString()
                : show.manifest().has("surface") ? show.manifest().get("surface").getAsString()
                : show.manifest().has("orientation") ? show.manifest().get("orientation").getAsString() : "wall_north";
        int lift = canvas.has("canvasLift") ? canvas.get("canvasLift").getAsInt() : (show.formatVersion() >= 2 ? 2 : 0);
        int offsetX = 0, offsetY = 0, offsetZ = 0;
        int backingThickness = canvas.has("backingThickness") ? canvas.get("backingThickness").getAsInt() : 1;
        int borderThickness = canvas.has("borderThickness") ? canvas.get("borderThickness").getAsInt() : 2;
        // New position metadata is a v2 feature. A legacy package must retain
        // its original origin even if an unrelated metadata writer added a
        // similarly named field.
        var positionOffset = show.formatVersion() >= 2 && canvas.has("positionOffset") && canvas.get("positionOffset").isJsonArray()
                ? canvas.getAsJsonArray("positionOffset")
                : show.formatVersion() >= 2 && show.manifest().has("canvasOffset") && show.manifest().get("canvasOffset").isJsonArray()
                ? show.manifest().getAsJsonArray("canvasOffset") : null;
        if (positionOffset != null) {
            if (positionOffset.size() >= 3) {
                offsetX = positionOffset.get(0).getAsInt();
                offsetY = positionOffset.get(1).getAsInt();
                offsetZ = positionOffset.get(2).getAsInt();
            }
        }
        return new PianoLayout(noteMin, noteMax, keyboardDepth, canvasGap, parseSurface(surfaceName),
                show.logicalWidth(), show.logicalHeight(), show.pixelScale(), lift, offsetX, offsetY, offsetZ,
                backingThickness, borderThickness);
    }

    private static Surface parseSurface(String value) {
        return switch (value) {
            case "floor" -> Surface.FLOOR;
            case "wall_south" -> Surface.WALL_SOUTH;
            case "wall_east" -> Surface.WALL_EAST;
            case "wall_west" -> Surface.WALL_WEST;
            default -> Surface.WALL_NORTH;
        };
    }

    public int noteMin() { return noteMin; }
    public int noteMax() { return noteMax; }
    public int keyboardDepth() { return keyboardDepth; }
    public int canvasGap() { return canvasGap; }
    public Surface surface() { return surface; }
    public int imageWidth() { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    public int pixelScale() { return pixelScale; }
    public int canvasLift() { return canvasLift; }
    public int canvasOffsetX() { return canvasOffsetX; }
    public int canvasOffsetY() { return canvasOffsetY; }
    public int canvasOffsetZ() { return canvasOffsetZ; }
    public int backingThickness() { return backingThickness; }
    public int borderThickness() { return borderThickness; }
    public int physicalWidth() { return imageWidth * pixelScale; }
    public int physicalHeight() { return imageHeight * pixelScale; }

    public boolean isBlack(int note) {
        int pitchClass = Math.floorMod(note, 12);
        for (int candidate : BLACK_PITCH_CLASSES) if (candidate == pitchClass) return true;
        return false;
    }

    public int whiteIndex(int note) {
        int index = 0;
        for (int candidate = noteMin; candidate < note; candidate++) if (!isBlack(candidate)) index++;
        return index;
    }

    public int whiteKeyCount() {
        int count = 0;
        for (int note = noteMin; note <= noteMax; note++) if (!isBlack(note)) count++;
        return count;
    }

    public int keyboardWidth() { return whiteKeyCount() * 2; }

    public BlockPos whiteKeyPos(BlockPos origin, int note, int depthIndex) {
        return origin.add(whiteIndex(note) * 2, 0, depthIndex);
    }

    public BlockPos blackKeyPos(BlockPos origin, int note, int depthIndex) {
        return origin.add(whiteIndex(note) * 2 + 1, 1, depthIndex);
    }

    /**
     * Returns the first physical block used by the requested key.  Keeping
     * this calculation in the layout makes playback and diagnostics use the
     * exact same key geometry as stage construction.
     */
    public BlockPos noteKeyBlock(BlockPos origin, int note) {
        int depth = Math.min(1, Math.max(0, keyboardDepth - 1));
        return isBlack(note) ? blackKeyPos(origin, note, depth) : whiteKeyPos(origin, note, depth);
    }

    public Vec3d noteLaunchPosition(BlockPos origin, int note) {
        BlockPos key = noteKeyBlock(origin, note);
        // White keys occupy two blocks in X, so launch from their centre
        // instead of the left half. Black keys occupy one block.
        double x = key.getX() + (isBlack(note) ? 0.5 : 1.0);
        double y = key.getY() + (isBlack(note) ? 1.8 : 1.2);
        return new Vec3d(x, y, key.getZ() + 0.5);
    }

    /** The top-left logical pixel anchor in world space. */
    public BlockPos canvasOrigin(BlockPos origin) {
        BlockPos base = switch (surface) {
            case FLOOR -> origin.add(0, 1 + canvasLift, keyboardDepth + canvasGap);
            case WALL_NORTH -> origin.add(0, canvasLift + physicalHeight() - 1, -canvasGap);
            case WALL_SOUTH -> origin.add(0, canvasLift + physicalHeight() - 1, keyboardDepth + canvasGap);
            case WALL_EAST -> origin.add(keyboardWidth() + canvasGap, canvasLift + physicalHeight() - 1, 0);
            case WALL_WEST -> origin.add(-canvasGap, canvasLift + physicalHeight() - 1, 0);
        };
        return base.add(canvasOffsetX, canvasOffsetY, canvasOffsetZ);
    }

    /** Top-left block of the physical footprint for a logical pixel. */
    public BlockPos canvasBlockPos(BlockPos origin, int x, int y) {
        BlockPos anchor = canvasOrigin(origin);
        int sx = x * pixelScale;
        int sy = y * pixelScale;
        return switch (surface) {
            case FLOOR -> anchor.add(sx, 0, sy);
            case WALL_NORTH, WALL_SOUTH -> anchor.add(sx, -sy, 0);
            case WALL_EAST -> anchor.add(0, -sy, sx);
            case WALL_WEST -> anchor.add(0, -sy, -sx);
        };
    }

    public List<BlockPos> canvasPixelPositions(BlockPos origin, int x, int y) {
        BlockPos topLeft = canvasBlockPos(origin, x, y);
        List<BlockPos> result = new ArrayList<>(pixelScale * pixelScale);
        for (int u = 0; u < pixelScale; u++) for (int v = 0; v < pixelScale; v++) {
            result.add(switch (surface) {
                case FLOOR -> topLeft.add(u, 0, v);
                case WALL_NORTH, WALL_SOUTH -> topLeft.add(u, -v, 0);
                case WALL_EAST -> topLeft.add(0, -v, u);
                case WALL_WEST -> topLeft.add(0, -v, -u);
            });
        }
        return result;
    }

    public Vec3d canvasPixelCenter(BlockPos origin, int x, int y) {
        BlockPos topLeft = canvasBlockPos(origin, x, y);
        double half = pixelScale / 2.0;
        return switch (surface) {
            case FLOOR -> new Vec3d(topLeft.getX() + half, topLeft.getY() + 0.5, topLeft.getZ() + half);
            case WALL_NORTH, WALL_SOUTH -> new Vec3d(topLeft.getX() + half, topLeft.getY() - half + 0.5, topLeft.getZ() + 0.5);
            case WALL_EAST, WALL_WEST -> new Vec3d(topLeft.getX() + 0.5, topLeft.getY() - half + 0.5, topLeft.getZ() + half);
        };
    }

    public Vec3d canvasNormal() {
        return switch (surface) {
            case FLOOR -> new Vec3d(0, 1, 0);
            case WALL_NORTH -> new Vec3d(0, 0, -1);
            case WALL_SOUTH -> new Vec3d(0, 0, 1);
            case WALL_EAST -> new Vec3d(1, 0, 0);
            case WALL_WEST -> new Vec3d(-1, 0, 0);
        };
    }

    /** Horizontal image axis in world space, used by motion scatter and previews. */
    public Vec3d canvasAxisX() {
        return switch (surface) {
            case FLOOR, WALL_NORTH, WALL_SOUTH -> new Vec3d(1, 0, 0);
            case WALL_EAST -> new Vec3d(0, 0, 1);
            case WALL_WEST -> new Vec3d(0, 0, -1);
        };
    }

    /** Image-down axis in world space. */
    public Vec3d canvasAxisY() {
        return switch (surface) {
            case FLOOR -> new Vec3d(0, 0, 1);
            case WALL_NORTH, WALL_SOUTH, WALL_EAST, WALL_WEST -> new Vec3d(0, -1, 0);
        };
    }

    public BlockPos canvasBackingPos(BlockPos origin, int x, int y) {
        Vec3d normal = canvasNormal();
        Direction direction = Direction.getFacing((float) normal.x, (float) normal.y, (float) normal.z);
        return canvasBlockPos(origin, x, y).offset(direction);
    }
}
