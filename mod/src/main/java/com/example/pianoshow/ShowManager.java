package com.example.pianoshow;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Server-authoritative playback, stage construction and visual effects. */
public final class ShowManager {
    private static final int STAGE_BLOCKS_PER_TICK = 1024;
    private final List<FlyingPixel> activeDisplays = new ArrayList<>();
    private final Deque<PendingPixel> pixelsToSpawn = new ArrayDeque<>();
    private final Deque<PendingPixel> pendingCommits = new ArrayDeque<>();
    private final Deque<StageBlock> stageQueue = new ArrayDeque<>();
    private final Map<BlockPos, Long> pressedUntil = new HashMap<>();
    private final Map<Integer, List<BlockPos>> keyPositions = new HashMap<>();
    private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
    private final Set<Integer> committedQueueIndices = new HashSet<>();
    private MinecraftServer server;
    private ShowPackage show;
    private PianoLayout layout;
    private ServerWorld world;
    private BlockPos origin = BlockPos.ORIGIN;
    private long playbackTick;
    private long visualTick;
    private double playbackPosition;
    private int nextEvent;
    private boolean playing;
    private boolean stageBuilt;
    private boolean loggedFlightSpawn;
    private int loggedFlightSamples;
    private int lastSpawned;
    private int lastCommitted;
    private double speed = 1.0;
    private long totalEntityPositionUpdates;
    private long totalDisplayTransitions;
    private long totalLostPayloads;
    private final Deque<Long> tickNanos = new ArrayDeque<>();
    private long tickStartNanos;

    public void attach(MinecraftServer server) {
        this.server = server;
        ServerTickEvents.START_SERVER_TICK.register(ignored -> startTick());
        ServerTickEvents.END_SERVER_TICK.register(ignored -> endTick());
    }

    public Path showDirectory() {
        if (server == null) throw new IllegalStateException("server is not attached");
        return server.getRunDirectory().resolve("config").resolve("piano-shows");
    }

    public String load(String fileName) throws Exception {
        Path directory = showDirectory().normalize();
        Path file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory)) throw new IllegalArgumentException("show path escapes config/piano-shows");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("show file does not exist: " + fileName);
        this.show = ShowPackage.load(file);
        this.layout = PianoLayout.from(show);
        this.playbackTick = 0;
        this.visualTick = 0;
        this.playbackPosition = 0;
        this.nextEvent = 0;
        this.pendingCommits.clear();
        this.pixelsToSpawn.clear();
        this.stageQueue.clear();
        this.keyPositions.clear();
        this.pressedUntil.clear();
        discardDisplays();
        this.originalBlocks.clear();
        this.committedQueueIndices.clear();
        this.loggedFlightSpawn = false;
        this.loggedFlightSamples = 0;
        this.lastSpawned = 0;
        this.lastCommitted = 0;
        this.totalEntityPositionUpdates = 0;
        this.totalDisplayTransitions = 0;
        this.totalLostPayloads = 0;
        this.tickNanos.clear();
        this.playing = false;
        this.stageBuilt = false;
        return show.manifest().get("showId").getAsString();
    }

    public void setOrigin(BlockPos origin) { this.origin = Objects.requireNonNull(origin); }
    public void setWorld(ServerWorld world) { this.world = Objects.requireNonNull(world); }
    public boolean hasStageOrigin() { return stageBuilt; }

    public void start(double speed) {
        if (show == null) throw new IllegalStateException("load a show first");
        if (speed <= 0 || speed > 16) throw new IllegalArgumentException("speed must be in (0, 16]");
        this.speed = speed;
        this.playing = true;
    }

    public void pause() { this.playing = false; }

    public void stop() {
        this.playing = false;
        this.playbackTick = 0;
        this.visualTick = 0;
        this.playbackPosition = 0;
        this.nextEvent = 0;
        this.pendingCommits.clear();
        this.pixelsToSpawn.clear();
        this.committedQueueIndices.clear();
        this.loggedFlightSpawn = false;
        this.loggedFlightSamples = 0;
        this.lastSpawned = 0;
        this.lastCommitted = 0;
        discardDisplays();
    }

    public boolean isLoaded() { return show != null; }
    public boolean isPlaying() { return playing; }
    public long playbackTick() { return playbackTick; }
    public int eventCount() { return show == null ? 0 : show.events().size(); }
    public int pixelCount() { return show == null ? 0 : show.pixels().size(); }
    public int activeDisplayCount() { return activeDisplays.size(); }
    public int activeFallingBlockCount() {
        int count = 0;
        for (FlyingPixel flying : activeDisplays) if (!flying.displayMode()) count++;
        return count;
    }
    public String performanceSummary() {
        long total = 0;
        long max = 0;
        for (long nanos : tickNanos) { total += nanos; max = Math.max(max, nanos); }
        long averageMicros = tickNanos.isEmpty() ? 0 : total / tickNanos.size() / 1_000;
        List<Long> samples = new ArrayList<>(tickNanos);
        samples.sort(Comparator.naturalOrder());
        long p95Micros = samples.isEmpty() ? 0 : samples.get(Math.min(samples.size() - 1, (int) Math.floor(samples.size() * 0.95))) / 1_000;
        return "avgTickUs=" + averageMicros
                + ", p95TickUs=" + p95Micros
                + ", activeDisplays=" + activeDisplayCount()
                + ", activeFallingBlocks=" + activeFallingBlockCount()
                + ", pendingPixels=" + pixelsToSpawn.size()
                + ", pendingCommits=" + pendingCommits.size()
                + ", entityPositionUpdates=" + totalEntityPositionUpdates
                + ", displayTransitions=" + totalDisplayTransitions
                + ", lostPayloads=" + totalLostPayloads;
    }
    public int committedPixelCount() { return committedQueueIndices.size(); }
    public int stageQueueSize() { return stageQueue.size(); }

    public String fallingDebugSummary() {
        return "activeFallingBlocks=" + activeFallingBlockCount()
                + ", activeDisplays=" + activeDisplayCount()
                + ", payloads=" + activeDisplays.size()
                + ", displayMode=" + (show == null ? "n/a" : show.visualMode())
                + ", queuedPixels=" + pixelsToSpawn.size()
                + ", pendingCommits=" + pendingCommits.size()
                + ", committedPixels=" + committedQueueIndices.size()
                + ", flightTicks=" + (show == null ? "n/a" : show.flightTicksMin() + ".." + show.flightTicksMax())
                + ", snapTicks=" + (show == null ? "n/a" : show.snapTicks())
                + ", motionMode=" + (show == null ? "n/a" : show.motionMode())
                + ", lastSpawned=" + lastSpawned
                + ", lastCommitted=" + lastCommitted;
    }

    public String debugTarget(int queueIndex) {
        if (world == null || show == null || layout == null) return "no show loaded";
        Pixel pixel = show.pixels().stream()
                .filter(candidate -> candidate.queueIndex() == queueIndex)
                .findFirst()
                .orElse(null);
        if (pixel == null) return "pixel queueIndex not found: " + queueIndex;
        Vec3d target = targetPosition(pixel);
        world.spawnParticles(ParticleTypes.END_ROD, target.x, target.y, target.z, 24, 0.15, 0.15, 0.15, 0.02);
        return "target queueIndex=" + queueIndex + " block=" + targetBlockPos(pixel).toShortString();
    }

    public int clearFallingEntities() {
        int count = activeDisplays.size();
        discardDisplays();
        return count;
    }

    public String layoutSummary() {
        if (show == null || layout == null) return "no show loaded";
        BlockPos canvas = layout.canvasOrigin(origin);
        BlockPos baseCanvas = canvas.add(-layout.canvasOffsetX(), -layout.canvasOffsetY(), -layout.canvasOffsetZ());
        return "surface=" + layout.surface().name().toLowerCase()
                + ", keyboard=" + (layout.noteMax() - layout.noteMin() + 1) + " keys"
                + ", canvas=" + show.imageWidth() + "x" + show.imageHeight() + " logical"
                + " (" + layout.physicalWidth() + "x" + layout.physicalHeight() + ", scale=" + layout.pixelScale() + ")"
                + ", visualMode=" + show.visualMode()
                + ", timingMode=" + show.timingMode()
                + ", motionMode=" + show.motionMode()
                + ", motionGravity=" + show.motionGravity()
                + ", motionDrag=" + show.motionDrag()
                + ", origin=" + origin.toShortString()
                + ", canvasBaseOrigin=" + baseCanvas.toShortString()
                + ", canvasOffset=[" + layout.canvasOffsetX() + "," + layout.canvasOffsetY() + "," + layout.canvasOffsetZ() + "]"
                + ", canvasOrigin=" + canvas.toShortString()
                + ", events=" + eventCount()
                + ", pixels=" + pixelCount()
                + ", queuedPixels=" + pixelsToSpawn.size()
                + ", activeDisplays=" + activeDisplayCount()
                + ", activeFallingBlocks=" + activeFallingBlockCount()
                + ", pendingCommits=" + pendingCommits.size()
                + ", committedPixels=" + committedPixelCount()
                + ", stageQueue=" + stageQueueSize();
    }

    public void manualNote(ServerWorld world, BlockPos position, int note) {
        this.world = world;
        triggerNote(note, 110, -1, false, position);
    }

    /** Build the currently loaded show at a world position selected by the placement item. */
    public void placeAt(ServerWorld world, BlockPos base) {
        if (!isLoaded()) throw new IllegalStateException("load a show first");
        if (isPlaying()) throw new IllegalStateException("stop the current show before moving the stage");
        // Preserve the note range encoded by the loaded package.  The old
        // placer always built an 88-key keyboard, which made custom projects
        // launch from keys that did not exist in the stage preview.
        buildKeyboard(world, base, layout.noteMin(), layout.noteMax());
    }

    /** Build using the note range from the loaded package. */
    public void buildKeyboard(ServerWorld world, BlockPos base) {
        if (!isLoaded() || layout == null) throw new IllegalStateException("load a show first");
        buildKeyboard(world, base, layout.noteMin(), layout.noteMax());
    }

    public void seek(long tick) {
        if (show == null) throw new IllegalStateException("load a show first");
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        playbackPosition = tick;
        playbackTick = tick;
        visualTick = tick;
        nextEvent = 0;
        while (nextEvent < show.events().size() && show.events().get(nextEvent).tick() < tick) nextEvent++;
        pixelsToSpawn.clear();
        pendingCommits.clear();
        committedQueueIndices.clear();
        discardDisplays();
    }

    public int restore() {
        if (world == null) return 0;
        int count = 0;
        for (Map.Entry<BlockPos, BlockState> entry : originalBlocks.entrySet()) {
            world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_ALL);
            count++;
        }
        originalBlocks.clear();
        committedQueueIndices.clear();
        return count;
    }

    public int clearPixels() {
        if (world == null || show == null) return 0;
        int count = 0;
        for (Pixel pixel : show.pixels()) {
            for (BlockPos position : layout.canvasPixelPositions(origin, pixel.x(), pixel.y())) {
                if (!world.getBlockState(position).isAir()) {
                    world.setBlockState(position, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    count++;
                }
            }
        }
        pendingCommits.clear();
        pixelsToSpawn.clear();
        committedQueueIndices.clear();
        discardDisplays();
        return count;
    }

    /** Queues the complete foreground keyboard and horizontal canvas stage. */
    public void buildKeyboard(ServerWorld world, BlockPos base, int noteMin, int noteMax) {
        if (show == null) throw new IllegalStateException("load a show first");
        this.world = world;
        this.origin = base;
        this.layout = new PianoLayout(noteMin, noteMax, layout.keyboardDepth(), layout.canvasGap(), layout.surface(),
                layout.imageWidth(), layout.imageHeight(), layout.pixelScale(), layout.canvasLift(),
                layout.canvasOffsetX(), layout.canvasOffsetY(), layout.canvasOffsetZ(),
                layout.backingThickness(), layout.borderThickness());
        this.stageQueue.clear();
        this.keyPositions.clear();
        this.pressedUntil.clear();
        this.stageBuilt = true;

        BlockState platform = Blocks.BLACK_CONCRETE.getDefaultState();
        for (int x = 0; x < layout.keyboardWidth(); x++) {
            for (int z = 0; z < layout.keyboardDepth(); z++) queueStage(base.add(x, -1, z), platform);
        }

        for (int note = noteMin; note <= noteMax; note++) {
            if (layout.isBlack(note)) {
                int depthStart = Math.min(1, Math.max(0, layout.keyboardDepth() - 1));
                int depthEnd = Math.max(depthStart + 1, layout.keyboardDepth() - 1);
                for (int z = depthStart; z < depthEnd; z++) queueKey(layout.blackKeyPos(base, note, z), note, PianoKeyBlock.KeyKind.BLACK);
            } else {
                for (int dx = 0; dx < 2; dx++) {
                    for (int z = 0; z < layout.keyboardDepth(); z++) {
                        queueKey(layout.whiteKeyPos(base, note, z).add(dx, 0, 0), note, PianoKeyBlock.KeyKind.WHITE);
                    }
                }
            }
        }

        queueCanvasStage(base);
    }

    private void queueKey(BlockPos position, int note, PianoKeyBlock.KeyKind kind) {
        BlockState state = PianoShowMod.PIANO_KEY.getDefaultState()
                .with(PianoKeyBlock.NOTE, note)
                .with(PianoKeyBlock.KEY_KIND, kind)
                .with(PianoKeyBlock.PRESSED, false);
        queueStage(position, state);
        keyPositions.computeIfAbsent(note, ignored -> new ArrayList<>()).add(position);
    }

    private void queueCanvasStage(BlockPos base) {
        int width = layout.physicalWidth();
        int height = layout.physicalHeight();
        BlockPos canvas = layout.canvasOrigin(base);
        BlockState backing = layoutBlock("backingBlock", Blocks.BLACK_CONCRETE);
        BlockState border = layoutBlock("borderBlock", Blocks.GRAY_CONCRETE);
        for (int depth = 0; depth < layout.backingThickness(); depth++) {
            for (int x = -1; x <= width; x++) for (int y = -1; y <= height; y++) {
                BlockPos pos = canvasStagePos(canvas, x, y, depth);
                queueStage(pos, backing);
            }
        }
        int borderWidth = layout.borderThickness();
        for (int x = -borderWidth; x <= width + borderWidth - 1; x++) {
            queueStage(canvasStagePos(canvas, x, -borderWidth, 0), border);
            queueStage(canvasStagePos(canvas, x, height + borderWidth - 1, 0), border);
        }
        for (int y = -borderWidth + 1; y <= height + borderWidth - 2; y++) {
            queueStage(canvasStagePos(canvas, -borderWidth, y, 0), border);
            queueStage(canvasStagePos(canvas, width + borderWidth - 1, y, 0), border);
        }
    }

    private BlockPos canvasStagePos(BlockPos anchor, int x, int y) {
        return canvasStagePos(anchor, x, y, 0);
    }

    private BlockPos canvasStagePos(BlockPos anchor, int x, int y, int depth) {
        BlockPos face = switch (layout.surface()) {
            case FLOOR -> anchor.add(x, 0, y);
            case WALL_NORTH, WALL_SOUTH -> anchor.add(x, -y, 0);
            case WALL_EAST -> anchor.add(0, -y, x);
            case WALL_WEST -> anchor.add(0, -y, -x);
        };
        return switch (layout.surface()) {
            case FLOOR -> face.add(0, -depth, 0);
            case WALL_NORTH -> face.add(0, 0, -depth);
            case WALL_SOUTH -> face.add(0, 0, depth);
            case WALL_EAST -> face.add(depth, 0, 0);
            case WALL_WEST -> face.add(-depth, 0, 0);
        };
    }

    private void queueStage(BlockPos position, BlockState state) { stageQueue.addLast(new StageBlock(position, state)); }

    private BlockState layoutBlock(String name, Block fallback) {
        if (show == null) return fallback.getDefaultState();
        JsonObject canvas = show.layout().has("canvas") ? show.layout().getAsJsonObject("canvas") : new JsonObject();
        if (!canvas.has(name)) return fallback.getDefaultState();
        Block block = Registries.BLOCK.get(Identifier.of(canvas.get(name).getAsString()));
        return block == null ? fallback.getDefaultState() : block.getDefaultState();
    }

    /** Runs before world/entity ticks so payload state is observed consistently. */
    private void startTick() {
        if (server == null) return;
        tickStartNanos = System.nanoTime();
        visualTick++;
        updateDisplays();
    }

    /** Runs after the world tick: schedule notes, spawn payloads and commit exact targets. */
    private void endTick() {
        if (server == null) return;
        lastSpawned = 0;
        lastCommitted = 0;
        commitStageBlocks();
        releasePressedKeys();
        if (playing && show != null && world != null) {
            long targetTick = (long) Math.floor(playbackPosition);
            while (nextEvent < show.events().size() && show.events().get(nextEvent).tick() <= targetTick) {
                NoteEvent event = show.events().get(nextEvent);
                triggerNote(event.note(), event.velocity(), nextEvent, true, null, event.durationTicks());
                nextEvent++;
            }
            playbackPosition += speed;
            playbackTick = (long) Math.floor(playbackPosition);
        }
        spawnDisplays();
        commitPixels();
        if (show != null && nextEvent >= show.events().size() && activeDisplays.isEmpty()
                && pendingCommits.isEmpty() && pixelsToSpawn.isEmpty()) {
            playing = false;
        }
        if (tickStartNanos != 0) {
            tickNanos.addLast(System.nanoTime() - tickStartNanos);
            while (tickNanos.size() > 200) tickNanos.removeFirst();
        }
    }

    private void commitStageBlocks() {
        if (world == null) return;
        int budget = STAGE_BLOCKS_PER_TICK;
        while (budget-- > 0 && !stageQueue.isEmpty()) {
            StageBlock block = stageQueue.pollFirst();
            world.setBlockState(block.position(), block.state(), Block.NOTIFY_ALL);
        }
    }

    private void triggerNote(int note, int velocity, int eventIndex, boolean allocatePixels, BlockPos manualPosition) {
        triggerNote(note, velocity, eventIndex, allocatePixels, manualPosition, 8);
    }

    private void triggerNote(int note, int velocity, int eventIndex, boolean allocatePixels, BlockPos manualPosition, long durationTicks) {
        if (world == null || show == null || layout == null) return;
        BlockPos sourceKey = manualPosition == null ? layout.noteKeyBlock(origin, note) : manualPosition;
        Vec3d launch = manualPosition == null ? layout.noteLaunchPosition(origin, note) : manualPosition.toCenterPos().add(0, 0.6, 0);
        float soundPitch = (float) Math.pow(2.0, (note - 60) / 12.0);
        world.playSound(null, launch.x, launch.y, launch.z, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), SoundCategory.RECORDS, Math.min(1.5f, velocity / 80.0f), Math.max(0.5f, Math.min(2.0f, soundPitch)));
        if (manualPosition == null) pressNote(note, durationTicks);
        if (allocatePixels && !show.pixels().isEmpty()) {
            int pixelStart = (int) ((long) eventIndex * show.pixels().size() / show.events().size());
            int pixelEnd = (int) ((long) (eventIndex + 1) * show.pixels().size() / show.events().size());
            for (int i = pixelStart; i < pixelEnd; i++) {
                pixelsToSpawn.addLast(new PendingPixel(show.pixels().get(i),
                        new NoteEvent(0, note, velocity, durationTicks, 0, 0),
                        eventIndex, note, sourceKey, launch));
            }
        }
        spawnNoteParticles(launch, velocity);
    }

    private void pressNote(int note, long durationTicks) {
        if (world == null) return;
        long releaseAt = playbackTick + Math.max(4, Math.min(40, durationTicks));
        for (BlockPos position : keyPositions.getOrDefault(note, List.of())) {
            BlockState state = world.getBlockState(position);
            if (state.isOf(PianoShowMod.PIANO_KEY)) {
                world.setBlockState(position, state.with(PianoKeyBlock.PRESSED, true), Block.NOTIFY_ALL);
                pressedUntil.put(position, releaseAt);
            }
        }
    }

    private void releasePressedKeys() {
        if (world == null || pressedUntil.isEmpty()) return;
        List<BlockPos> released = new ArrayList<>();
        for (Map.Entry<BlockPos, Long> entry : pressedUntil.entrySet()) {
            if (playbackTick < entry.getValue()) continue;
            BlockState state = world.getBlockState(entry.getKey());
            if (state.isOf(PianoShowMod.PIANO_KEY)) world.setBlockState(entry.getKey(), state.with(PianoKeyBlock.PRESSED, false), Block.NOTIFY_ALL);
            released.add(entry.getKey());
        }
        released.forEach(pressedUntil::remove);
    }

    /** Lightweight note feedback; all block entities remain image payloads. */
    private void spawnNoteParticles(Vec3d start, int velocity) {
        int min = show.maxSparkBurstMin();
        int max = Math.max(min, show.maxSparkBurstMax());
        int count = min + (int) Math.round((max - min) * Math.max(0, Math.min(127, velocity)) / 127.0);
        if (count > 0) {
            world.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z,
                    count, 0.18, 0.12, 0.18, 0.025);
        }
    }

    private void spawnDisplays() {
        if (world == null || show == null || layout == null) return;
        TimingController.Budget timing = TimingController.budget(show, playbackTick, nextEvent,
                pixelsToSpawn.size(), activeDisplays.size());
        int spawned = 0;
        int activeLimit = "display".equals(show.visualMode())
                ? show.maxActiveDisplays() : show.maxActiveFallingBlocks();
        while (!pixelsToSpawn.isEmpty() && spawned < timing.spawnPerTick() && activeDisplays.size() < activeLimit) {
            PendingPixel pending = pixelsToSpawn.pollFirst();
            Vec3d start = pending.launchPosition() != null
                    ? pending.launchPosition()
                    : layout.noteLaunchPosition(origin, pending.sourceNote());
            Vec3d target = targetPosition(pending.pixel());
            BlockState payloadState = blockState(pending.pixel().paletteIndex());
            long duration = "adaptive".equals(show.timingMode())
                    ? timing.flightTicks()
                    : show.flightTicksForVelocity(pending.event().velocity());
            long seed = show.randomSeed() ^ ((long) pending.eventIndex() * 0x9E3779B97F4A7C15L)
                    ^ pending.pixel().queueIndex();
            // Legacy v1 payloads used a small fixed visual scatter. Keep that
            // fallback while v2 packages opt into the configurable radius.
            double scatterRadius = show.formatVersion() >= 2 ? show.scatterRadius() : 0.12;
            boolean vanillaPhysics = "vanilla".equals(show.motionMode())
                    && "physical".equals(show.visualMode());
            MotionPath motion = "ballistic".equals(show.motionMode()) || vanillaPhysics
                    ? (vanillaPhysics
                    ? MotionCalculator.vanillaPath(layout, start, target, duration,
                    show.motionGravity(), show.motionDrag(), scatterRadius, seed)
                    : MotionCalculator.ballistic(layout, start, target, duration,
                    show.motionGravity(), show.motionDrag(), scatterRadius, seed))
                    : MotionCalculator.arc(layout, start, target, duration,
                    show.motionArcHeight(), scatterRadius, seed);
            Vec3d initialVelocity = vanillaPhysics
                    ? MotionCalculator.vanillaVelocity(start, target, duration,
                    show.motionGravity(), show.motionDrag())
                    : Vec3d.ZERO;
            Entity display = "display".equals(show.visualMode())
                    ? createBlockDisplay(payloadState, start, layout.pixelScale())
                    : createFallingBlock(payloadState, start, initialVelocity, vanillaPhysics);
            if (display == null) {
                world.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z, 2, 0.2, 0.2, 0.2, 0.03);
                pendingCommits.addLast(pending);
                spawned++;
                continue;
            }
            activeDisplays.add(new FlyingPixel(display, payloadState, pending.pixel(), pending.eventIndex(), pending.sourceNote(),
                    pending.sourceKeyBlock(), motion, visualTick, duration, show.snapTicks(), true,
                    "display".equals(show.visualMode()), vanillaPhysics, initialVelocity,
                    vanillaPhysics ? Math.max(duration + 80, 100) : duration));
            lastSpawned++;
            if (!loggedFlightSpawn || loggedFlightSamples < 12 || pending.eventIndex() % 128 == 0) {
                PianoShowMod.LOGGER.info("Piano payload flight started: queueIndex={}, eventIndex={}, note={}, key={}, entity={}, block={}, start={}, target={}, durationTicks={}, motionMode={}, gravity={}, drag={}, initialVelocity={}, motionNbt=[{}d,{}d,{}d]",
                        pending.pixel().queueIndex(), pending.eventIndex(), pending.sourceNote(), pending.sourceKeyBlock(),
                        display.getUuid(), payloadState.getBlock(), start, target, duration, show.motionMode(),
                        show.motionGravity(), show.motionDrag(), initialVelocity,
                        initialVelocity.x, initialVelocity.y, initialVelocity.z);
                loggedFlightSpawn = true;
                loggedFlightSamples++;
            }
            spawned++;
        }
    }

    private Entity createFallingBlock(BlockState state, Vec3d start, Vec3d initialVelocity, boolean vanillaPhysics) {
        try {
            // spawnFromBlock inserts the entity immediately (the constructor
            // is private in vanilla), so all initial state is applied before
            // returning it.  Prefer an air staging cell at the key and move
            // the entity to the precise launch point below.
            BlockPos launchPos = BlockPos.ofFloored(start);
            BlockPos spawnPos = null;
            for (int offset = 0; offset <= 3; offset++) {
                BlockPos candidate = launchPos.up(offset);
                if (world.getBlockState(candidate).isAir()) {
                    spawnPos = candidate;
                    break;
                }
            }
            if (spawnPos == null) {
                PianoShowMod.LOGGER.warn("No air staging position available for falling block at {}", start);
                return null;
            }
            FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, spawnPos, state);
            falling.setPosition(start);
            falling.setOnGround(false);
            // Vanilla mode intentionally leaves gravity, drag and collision to
            // FallingBlockEntity. The manual physical mode keeps the old
            // no-gravity path for compatibility with arc/ballistic motion.
            falling.setNoGravity(!vanillaPhysics);
            falling.setInvulnerable(true);
            // Never let vanilla place this entity at a collision position; the mod owns the exact target commit.
            falling.setDestroyedOnLanding();
            falling.dropItem = false;
            Vec3d motion = vanillaPhysics ? initialVelocity : Vec3d.ZERO;
            falling.setVelocity(motion);
            if (vanillaPhysics) writeVanillaMotion(falling, motion);
            falling.setFallingBlockPos(spawnPos);
            return falling;
        } catch (RuntimeException exception) {
            PianoShowMod.LOGGER.warn("Unable to create falling block for piano show", exception);
            return null;
        }
    }

    /** Write the canonical entity NBT Motion:[vx,vy,vz] payload for debugging,
     * save/reload compatibility, and parity with summon commands. */
    private void writeVanillaMotion(FallingBlockEntity falling, Vec3d motion) {
        // Preserve the entity's complete state while replacing only Motion;
        // feeding a partial compound to Entity.readNbt would reset position
        // and FallingBlock-specific fields on some mappings.
        NbtCompound nbt = falling.writeNbt(new NbtCompound());
        NbtList values = new NbtList();
        values.add(NbtDouble.of(motion.x));
        values.add(NbtDouble.of(motion.y));
        values.add(NbtDouble.of(motion.z));
        nbt.put("Motion", values);
        falling.readNbt(nbt);
        // Keep the live server velocity explicitly synchronized with the NBT
        // value; this is harmless for [0.0d,0.0d,0.0d] and preserves vanilla
        // gravity for the following tick.
        falling.setVelocity(motion);
        PianoShowMod.LOGGER.debug("Vanilla Motion NBT entity={} Motion:[{}d,{}d,{}d]", falling.getUuid(), motion.x, motion.y, motion.z);
    }

    private Entity createBlockDisplay(BlockState state, Vec3d start, int pixelScale) {
        try {
            DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world);
            if (display == null) return null;
            display.setPosition(start);
            display.setNoGravity(true);
            // The spawn packet establishes the first transform. Movement is
            // scheduled on the following server tick so clients have a stable
            // start pose to interpolate from.
            applyDisplayNbt(display, state, new Vec3d(0, 0, 0), pixelScale, 1);
            if (!world.spawnEntity(display)) {
                PianoShowMod.LOGGER.warn("Server rejected piano Display payload entity={} at {}", display.getUuid(), start);
                display.discard();
                return null;
            }
            return display;
        } catch (RuntimeException exception) {
            PianoShowMod.LOGGER.warn("Unable to create block display for piano show", exception);
            return null;
        }
    }

    private void updateDisplays() {
        if (activeDisplays.isEmpty()) return;
        List<FlyingPixel> finished = new ArrayList<>();
        for (FlyingPixel flying : activeDisplays) {
            if (flying.vanillaPhysics()) {
                long elapsed = Math.max(0, visualTick - flying.startTick());
                Vec3d position = flying.entity().getPos();
                Vec3d velocity = flying.entity().getVelocity();
                String reason = null;
                boolean removed = !flying.entity().isAlive() || flying.entity().isRemoved();
                if (removed) reason = "entity_removed";
                else if (flying.entity().isOnGround()) reason = "collision_or_landing";
                else if (elapsed >= flying.maxLifetimeTicks()) reason = "timeout";
                else if (outsideMotionBounds(position, flying.motion())) reason = "out_of_bounds";
                if (reason != null) {
                    flying.entity().discard();
                    if (flying.commit() && flying.pixel() != null) {
                        pendingCommits.addLast(PendingPixel.forCommit(flying.pixel()));
                        if (!"collision_or_landing".equals(reason)) totalLostPayloads++;
                        PianoShowMod.LOGGER.info("Vanilla Motion payload finished: reason={}, queueIndex={}, eventIndex={}, note={}, key={}, entity={}, position={}, velocity={}, target={}, initialVelocity={}",
                                reason, flying.pixel().queueIndex(), flying.sourceEventIndex(), flying.sourceNote(),
                                flying.sourceKeyBlock(), flying.entity().getUuid(), position, velocity, flying.motion().target(), flying.initialVelocity());
                    }
                    finished.add(flying);
                }
                continue;
            }
            if (!flying.entity().isAlive() || flying.entity().isRemoved()) {
                if (flying.commit() && flying.pixel() != null) {
                    pendingCommits.addLast(PendingPixel.forCommit(flying.pixel()));
                    totalLostPayloads++;
                    PianoShowMod.LOGGER.warn("Payload entity disappeared before target: queueIndex={}, eventIndex={}, note={}, key={}, entity={}, lastPosition={}, target={}",
                            flying.pixel().queueIndex(), flying.sourceEventIndex(), flying.sourceNote(), flying.sourceKeyBlock(),
                            flying.entity().getUuid(), flying.entity().getPos(), flying.motion().target());
                }
                finished.add(flying);
                continue;
            }
            long elapsed = Math.max(0, visualTick - flying.startTick());
            long remaining = flying.durationTicks() - elapsed;
            double progress = Math.min(1.0, elapsed / (double) flying.durationTicks());
            if (flying.displayMode()) {
                // Keep the entity alive for one server tick after the client
                // interpolation duration so the final pose can be rendered
                // before the payload is removed and committed.
                if (remaining < 0) {
                    flying.entity().discard();
                    if (flying.commit() && flying.pixel() != null) pendingCommits.addLast(PendingPixel.forCommit(flying.pixel()));
                    finished.add(flying);
                } else {
                    int maxSegments = "ballistic".equals(show.motionMode()) ? 4 : 2;
                    List<Vec3d> frames = flying.motion().keyframes(maxSegments);
                    int segments = frames.size() - 1;
                    for (int phase = 1; phase < frames.size(); phase++) {
                        long boundary = phase == 1 ? 1 : 1 + Math.round((phase - 1) * flying.durationTicks() / (double) segments);
                        // Start interpolating on the first server tick after
                        // spawn. Later phases begin when the previous segment
                        // reaches its boundary.
                        if (elapsed == boundary) {
                            long nextBoundary = 1 + Math.round(phase * flying.durationTicks() / (double) segments);
                            long segmentTicks = Math.max(1, nextBoundary - boundary);
                            setDisplayTranslation(flying.entity(), flying.state(), frames.get(phase).subtract(flying.motion().start()), layout.pixelScale(), (int) segmentTicks);
                            totalDisplayTransitions++;
                            PianoShowMod.LOGGER.debug("Piano Display interpolation phase={} queueIndex={} eventIndex={} note={} key={} entity={} durationTicks={} motionMode={}",
                                    phase, flying.pixel().queueIndex(), flying.sourceEventIndex(), flying.sourceNote(), flying.sourceKeyBlock(),
                                    flying.entity().getUuid(), segmentTicks, show.motionMode());
                            break;
                        }
                    }
                }
                continue;
            }
            Vec3d position = remaining <= flying.snapTicks() ? flying.motion().target() : flying.motion().positionAt(elapsed);
            Vec3d previous = elapsed <= 0 ? flying.motion().start() : flying.motion().positionAt(elapsed - 1);
            flying.entity().setOnGround(false);
            flying.entity().setNoGravity(true);
            flying.entity().setVelocity(position.subtract(previous));
            flying.entity().refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            flying.entity().updateTrackedPosition(position.x, position.y, position.z);
            totalEntityPositionUpdates++;
            if (progress >= 1.0) {
                flying.entity().discard();
                if (flying.commit() && flying.pixel() != null) pendingCommits.addLast(PendingPixel.forCommit(flying.pixel()));
                finished.add(flying);
            }
        }
        activeDisplays.removeAll(finished);
    }

    private boolean outsideMotionBounds(Vec3d position, MotionPath motion) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            return true;
        }
        Vec3d start = motion.start();
        Vec3d target = motion.target();
        return Math.abs(position.x - start.x) > 512 || Math.abs(position.x - target.x) > 512
                || Math.abs(position.y - start.y) > 512 || Math.abs(position.y - target.y) > 512
                || Math.abs(position.z - start.z) > 512 || Math.abs(position.z - target.z) > 512;
    }

    private void setDisplayTranslation(Entity entity, BlockState state, Vec3d translation, int pixelScale, int duration) {
        if (!(entity instanceof DisplayEntity display)) return;
        applyDisplayNbt(display, state, translation, pixelScale, Math.max(1, duration));
    }

    private void applyDisplayNbt(DisplayEntity display, BlockState state, Vec3d translation, int scale, int duration) {
        NbtCompound nbt = new NbtCompound();
        // DisplayEntity.readNbt delegates BlockDisplayEntity's block_state
        // decoder. Omitting this field resets the payload to air, so every
        // interpolation update must carry the complete payload state.
        nbt.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound transformation = new NbtCompound();
        transformation.put("translation", floats(translation.x, translation.y, translation.z));
        transformation.put("scale", floats(scale, scale, scale));
        transformation.put("left_rotation", floats(0, 0, 0, 1));
        transformation.put("right_rotation", floats(0, 0, 0, 1));
        nbt.put("transformation", transformation);
        nbt.putInt("interpolation_duration", duration);
        nbt.putInt("start_interpolation", 0);
        // The default view range is only 64 blocks. A 256x256 canvas can be
        // farther away than that, which otherwise makes the flight appear to
        // disappear even though the entity is alive on the server.
        nbt.putFloat("view_range", 8.0f);
        nbt.putFloat("width", Math.max(1, scale));
        nbt.putFloat("height", Math.max(1, scale));
        display.readNbt(nbt);
    }

    private NbtList floats(double... values) {
        NbtList list = new NbtList();
        for (double value : values) list.add(NbtFloat.of((float) value));
        return list;
    }

    private void commitPixels() {
        if (world == null || show == null) return;
        int budget = show.maxCommitPerTick();
        while (budget > 0 && !pendingCommits.isEmpty()) {
            PendingPixel pending = pendingCommits.pollFirst();
            if (!committedQueueIndices.add(pending.pixel().queueIndex())) continue;
            int footprint = layout.pixelScale() * layout.pixelScale();
            if (footprint > budget) {
                committedQueueIndices.remove(pending.pixel().queueIndex());
                pendingCommits.addFirst(pending);
                break;
            }
            BlockState state = blockState(pending.pixel().paletteIndex());
            for (BlockPos position : layout.canvasPixelPositions(origin, pending.pixel().x(), pending.pixel().y())) {
                originalBlocks.putIfAbsent(position, world.getBlockState(position));
                world.setBlockState(position, state, Block.NOTIFY_ALL);
                lastCommitted++;
            }
            budget -= footprint;
        }
    }

    private BlockState blockState(int paletteIndex) {
        if (show == null || paletteIndex < 0 || paletteIndex >= show.palette().size()) return PianoShowMod.FALLBACK_BLOCK.getDefaultState();
        ShowPackage.PaletteEntry entry = show.palette().get(paletteIndex);
        Block block = Registries.BLOCK.get(entry.block());
        return block == null ? PianoShowMod.FALLBACK_BLOCK.getDefaultState() : block.getDefaultState();
    }

    private Vec3d targetPosition(Pixel pixel) { return layout.canvasPixelCenter(origin, pixel.x(), pixel.y()); }
    private BlockPos targetBlockPos(Pixel pixel) { return layout.canvasBlockPos(origin, pixel.x(), pixel.y()); }

    private void discardDisplays() {
        activeDisplays.forEach(flying -> flying.entity().discard());
        activeDisplays.clear();
    }

    private record PendingPixel(Pixel pixel, NoteEvent event, int eventIndex, int sourceNote,
                                BlockPos sourceKeyBlock, Vec3d launchPosition) {
        private static PendingPixel forCommit(Pixel pixel) {
            return new PendingPixel(pixel, null, -1, -1, null, null);
        }
    }
    private record FlyingPixel(Entity entity, BlockState state, Pixel pixel, int sourceEventIndex, int sourceNote,
                               BlockPos sourceKeyBlock, MotionPath motion, long startTick,
                                long durationTicks,
                                int snapTicks, boolean commit, boolean displayMode,
                                boolean vanillaPhysics, Vec3d initialVelocity, long maxLifetimeTicks) {}
    private record StageBlock(BlockPos position, BlockState state) {}
}
