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
        return "surface=" + layout.surface().name().toLowerCase()
                + ", keyboard=" + (layout.noteMax() - layout.noteMin() + 1) + " keys"
                + ", canvas=" + show.imageWidth() + "x" + show.imageHeight() + " logical"
                + " (" + layout.physicalWidth() + "x" + layout.physicalHeight() + ", scale=" + layout.pixelScale() + ")"
                + ", visualMode=" + show.visualMode()
                + ", timingMode=" + show.timingMode()
                + ", origin=" + origin.toShortString()
                + ", canvasOrigin=" + canvas.toShortString()
                + ", events=" + eventCount()
                + ", pixels=" + pixelCount()
                + ", queuedPixels=" + pixelsToSpawn.size()
                + ", activeFallingBlocks=" + activeFallingBlockCount()
                + ", pendingCommits=" + pendingCommits.size()
                + ", committedPixels=" + committedPixelCount()
                + ", stageQueue=" + stageQueueSize();
    }

    public void manualNote(ServerWorld world, BlockPos position, int note) {
        this.world = world;
        triggerNote(note, 110, -1, false, position);
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
                layout.imageWidth(), layout.imageHeight(), layout.pixelScale(), layout.canvasLift());
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
        for (int x = -1; x <= width; x++) for (int y = -1; y <= height; y++) {
            BlockPos pos = canvasStagePos(canvas, x, y);
            queueStage(pos, backing);
        }
        for (int x = -2; x <= width + 1; x++) {
            queueStage(canvasStagePos(canvas, x, -2), border);
            queueStage(canvasStagePos(canvas, x, height + 1), border);
        }
        for (int y = -1; y <= height; y++) {
            queueStage(canvasStagePos(canvas, -2, y), border);
            queueStage(canvasStagePos(canvas, width + 1, y), border);
        }
    }

    private BlockPos canvasStagePos(BlockPos anchor, int x, int y) {
        return switch (layout.surface()) {
            case FLOOR -> anchor.add(x, 0, y);
            case WALL_NORTH, WALL_SOUTH -> anchor.add(x, -y, 0);
            case WALL_EAST -> anchor.add(0, -y, x);
            case WALL_WEST -> anchor.add(0, -y, -x);
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

    /** Runs before vanilla world/entity ticks so FallingBlockEntity cannot land early. */
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
        Vec3d launch = manualPosition == null ? layout.noteLaunchPosition(origin, note) : manualPosition.toCenterPos().add(0, 0.6, 0);
        float soundPitch = (float) Math.pow(2.0, (note - 60) / 12.0);
        world.playSound(null, launch.x, launch.y, launch.z, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), SoundCategory.RECORDS, Math.min(1.5f, velocity / 80.0f), Math.max(0.5f, Math.min(2.0f, soundPitch)));
        if (manualPosition == null) pressNote(note, durationTicks);
        if (allocatePixels && !show.pixels().isEmpty()) {
            int pixelStart = (int) ((long) eventIndex * show.pixels().size() / show.events().size());
            int pixelEnd = (int) ((long) (eventIndex + 1) * show.pixels().size() / show.events().size());
            for (int i = pixelStart; i < pixelEnd; i++) pixelsToSpawn.addLast(new PendingPixel(show.pixels().get(i), new NoteEvent(0, note, velocity, durationTicks, 0, 0), eventIndex));
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
        while (!pixelsToSpawn.isEmpty() && spawned < timing.spawnPerTick() && activeDisplays.size() < show.maxActiveFallingBlocks()) {
            PendingPixel pending = pixelsToSpawn.pollFirst();
            Vec3d start = layout.noteLaunchPosition(origin, pending.event().note());
            Vec3d target = targetPosition(pending.pixel());
            Entity display = "display".equals(show.visualMode())
                    ? createBlockDisplay(blockState(pending.pixel().paletteIndex()), start, layout.pixelScale())
                    : createFallingBlock(blockState(pending.pixel().paletteIndex()), start);
            if (display == null) {
                world.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z, 2, 0.2, 0.2, 0.2, 0.03);
                pendingCommits.addLast(pending);
                spawned++;
                continue;
            }
            long duration = "adaptive".equals(show.timingMode())
                    ? timing.flightTicks()
                    : show.flightTicksForVelocity(pending.event().velocity());
            long seed = show.randomSeed() ^ ((long) pending.eventIndex() * 0x9E3779B97F4A7C15L)
                    ^ pending.pixel().queueIndex();
            SplittableRandom random = new SplittableRandom(seed);
            Vec3d scatter = new Vec3d(random.nextDouble(-0.12, 0.12), 0, random.nextDouble(-0.12, 0.12));
            Vec3d mid = start.lerp(target, 0.52).add(0, 1.5 + Math.min(6.0, target.distanceTo(start) * 0.08), 0);
            activeDisplays.add(new FlyingPixel(display, pending.pixel(), start, mid, target, visualTick, duration, 1.5,
                    scatter, random.nextDouble(Math.PI * 2), show.snapTicks(), true,
                    "display".equals(show.visualMode())));
            lastSpawned++;
            if (!loggedFlightSpawn) {
                PianoShowMod.LOGGER.info("Piano payload flight started: queueIndex={}, start={}, target={}, durationTicks={}",
                        pending.pixel().queueIndex(), start, target, duration);
                loggedFlightSpawn = true;
            }
            spawned++;
        }
    }

    private Entity createFallingBlock(BlockState state, Vec3d start) {
        try {
            // spawnFromBlock replaces its source with the fluid state. Prefer the actual launch cell
            // so the spawn packet starts at the key, then search upward only if that cell is occupied.
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
            falling.refreshPositionAfterTeleport(start);
            falling.setOnGround(false);
            falling.setNoGravity(true);
            falling.setInvulnerable(true);
            // Never let vanilla place this entity at a collision position; the mod owns the exact target commit.
            falling.setDestroyedOnLanding();
            falling.dropItem = false;
            falling.setVelocity(Vec3d.ZERO);
            falling.setFallingBlockPos(spawnPos);
            return falling;
        } catch (RuntimeException exception) {
            PianoShowMod.LOGGER.warn("Unable to create falling block for piano show", exception);
            return null;
        }
    }

    private Entity createBlockDisplay(BlockState state, Vec3d start, int pixelScale) {
        try {
            DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world);
            if (display == null) return null;
            display.setPosition(start);
            display.setNoGravity(true);
            applyDisplayNbt(display, state, new Vec3d(0, 0, 0), pixelScale, 8);
            world.spawnEntity(display);
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
            if (!flying.entity().isAlive() || flying.entity().isRemoved()) {
                if (flying.commit() && flying.pixel() != null) {
                    pendingCommits.addLast(new PendingPixel(flying.pixel(), null, -1));
                    totalLostPayloads++;
                    PianoShowMod.LOGGER.warn("Payload entity disappeared before target: queueIndex={}, lastPosition={}, target={}",
                            flying.pixel().queueIndex(), flying.entity().getPos(), flying.target());
                }
                finished.add(flying);
                continue;
            }
            long elapsed = Math.max(0, visualTick - flying.startTick());
            long remaining = flying.durationTicks() - elapsed;
            double progress = Math.min(1.0, elapsed / (double) flying.durationTicks());
            if (flying.displayMode()) {
                if (remaining <= 0) {
                    flying.entity().discard();
                    if (flying.commit() && flying.pixel() != null) pendingCommits.addLast(new PendingPixel(flying.pixel(), null, -1));
                    finished.add(flying);
                } else if (elapsed == flying.durationTicks() / 2) {
                    setDisplayTranslation(flying.entity(), flying.mid().subtract(flying.start()), layout.pixelScale());
                    totalDisplayTransitions++;
                } else if (elapsed == flying.durationTicks() - 1) {
                    setDisplayTranslation(flying.entity(), flying.target().subtract(flying.start()), layout.pixelScale());
                    totalDisplayTransitions++;
                }
                continue;
            }
            Vec3d position;
            if (remaining <= flying.snapTicks()) {
                position = flying.target();
            } else {
                double arc = Math.sin(progress * Math.PI) * flying.arcHeight();
                double scatterEnvelope = Math.sin(progress * Math.PI);
                Vec3d scatter = flying.scatter().multiply(scatterEnvelope);
                double phase = flying.phase();
                Vec3d phaseOffset = new Vec3d(Math.cos(phase + progress * Math.PI * 2) * scatter.x,
                        0, Math.sin(phase + progress * Math.PI * 2) * scatter.z);
                position = flying.start().lerp(flying.target(), progress).add(phaseOffset.x, arc, phaseOffset.z);
            }
            flying.entity().setOnGround(false);
            flying.entity().setNoGravity(true);
            flying.entity().setVelocity(Vec3d.ZERO);
            flying.entity().refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            flying.entity().updateTrackedPosition(position.x, position.y, position.z);
            totalEntityPositionUpdates++;
            if (progress >= 1.0) {
                flying.entity().discard();
                if (flying.commit() && flying.pixel() != null) pendingCommits.addLast(new PendingPixel(flying.pixel(), null, -1));
                finished.add(flying);
            }
        }
        activeDisplays.removeAll(finished);
    }

    private void setDisplayTranslation(Entity entity, Vec3d translation, int pixelScale) {
        if (!(entity instanceof DisplayEntity display)) return;
        applyDisplayNbt(display, null, translation, pixelScale, 2);
    }

    private void applyDisplayNbt(DisplayEntity display, BlockState state, Vec3d translation, int scale, int duration) {
        NbtCompound nbt = new NbtCompound();
        if (state != null) nbt.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound transformation = new NbtCompound();
        transformation.put("translation", floats(translation.x, translation.y, translation.z));
        transformation.put("scale", floats(scale, scale, scale));
        transformation.put("left_rotation", floats(0, 0, 0, 1));
        transformation.put("right_rotation", floats(0, 0, 0, 1));
        nbt.put("transformation", transformation);
        nbt.putInt("interpolation_duration", duration);
        nbt.putInt("start_interpolation", 0);
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

    private record PendingPixel(Pixel pixel, NoteEvent event, int eventIndex) {}
    private record FlyingPixel(Entity entity, Pixel pixel, Vec3d start, Vec3d mid, Vec3d target, long startTick,
                                long durationTicks, double arcHeight, Vec3d scatter, double phase,
                                int snapTicks, boolean commit, boolean displayMode) {}
    private record StageBlock(BlockPos position, BlockState state) {}
}
