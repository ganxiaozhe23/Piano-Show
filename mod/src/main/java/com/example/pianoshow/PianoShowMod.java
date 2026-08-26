package com.example.pianoshow;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PianoShowMod implements ModInitializer {
    public static final String MOD_ID = "piano_show";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Block PIANO_KEY = Registry.register(
            Registries.BLOCK,
            Identifier.of(MOD_ID, "piano_key"),
            new PianoKeyBlock(AbstractBlock.Settings.create().strength(2.0f).nonOpaque())
    );
    public static final Block FALLBACK_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of(MOD_ID, "fallback_pixel"),
            new Block(AbstractBlock.Settings.create().strength(1.0f))
    );
    public static final Item PIANO_KEY_ITEM = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "piano_key"),
            new BlockItem(PIANO_KEY, new Item.Settings())
    );
    public static final Item FALLBACK_BLOCK_ITEM = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "fallback_pixel"),
            new BlockItem(FALLBACK_BLOCK, new Item.Settings())
    );

    static final ShowManager SHOW_MANAGER = new ShowManager();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(SHOW_MANAGER::attach);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld serverWorld && world.getBlockState(hit.getBlockPos()).isOf(PIANO_KEY)) {
                int note = world.getBlockState(hit.getBlockPos()).get(PianoKeyBlock.NOTE);
                SHOW_MANAGER.manualNote(serverWorld, hit.getBlockPos(), note);
                return net.minecraft.util.ActionResult.SUCCESS;
            }
            return net.minecraft.util.ActionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("piano")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("load").then(CommandManager.argument("file", StringArgumentType.word())
                                .executes(context -> load(context.getSource(), StringArgumentType.getString(context, "file")))))
                        .then(CommandManager.literal("play").executes(context -> play(context.getSource(), 1.0))
                                .then(CommandManager.argument("speed", DoubleArgumentType.doubleArg(0.05, 16.0))
                                        .executes(context -> play(context.getSource(), DoubleArgumentType.getDouble(context, "speed")))))
                        .then(CommandManager.literal("pause").executes(context -> {
                            SHOW_MANAGER.pause();
                            context.getSource().sendFeedback(() -> Text.literal("Piano show paused"), true);
                            return 1;
                        }))
                        .then(CommandManager.literal("stop").executes(context -> {
                            SHOW_MANAGER.stop();
                            context.getSource().sendFeedback(() -> Text.literal("Piano show stopped"), true);
                            return 1;
                        }))
                        .then(CommandManager.literal("seek").then(CommandManager.argument("tick", LongArgumentType.longArg(0))
                                .executes(context -> {
                                    try {
                                        long tick = LongArgumentType.getLong(context, "tick");
                                        SHOW_MANAGER.seek(tick);
                                        context.getSource().sendFeedback(() -> Text.literal("Piano show seeked to tick " + tick), true);
                                        return 1;
                                    } catch (Exception exception) {
                                        context.getSource().sendError(Text.literal("Unable to seek show: " + exception.getMessage()));
                                        return 0;
                                    }
                                })))
                        .then(CommandManager.literal("clear").executes(context -> {
                            int count = SHOW_MANAGER.clearPixels();
                            context.getSource().sendFeedback(() -> Text.literal("Cleared " + count + " pixel blocks"), true);
                            return 1;
                        }))
                        .then(CommandManager.literal("restore").executes(context -> {
                            int count = SHOW_MANAGER.restore();
                            context.getSource().sendFeedback(() -> Text.literal("Restored " + count + " original blocks"), true);
                            return 1;
                        }))
                        .then(CommandManager.literal("preview").executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(SHOW_MANAGER.layoutSummary()), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("build")
                                .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> build(context.getSource(), new BlockPos(
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"))))))))
                        .then(CommandManager.literal("status").executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal("loaded=" + SHOW_MANAGER.isLoaded() + ", playing=" + SHOW_MANAGER.isPlaying() + ", tick=" + SHOW_MANAGER.playbackTick()), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("debug")
                                .then(CommandManager.literal("falling").executes(context -> {
                                    context.getSource().sendFeedback(() -> Text.literal(SHOW_MANAGER.fallingDebugSummary()), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("target")
                                        .then(CommandManager.argument("queueIndex", IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    String message = SHOW_MANAGER.debugTarget(IntegerArgumentType.getInteger(context, "queueIndex"));
                                                    context.getSource().sendFeedback(() -> Text.literal(message), false);
                                                    return message.startsWith("pixel queueIndex not found") || message.equals("no show loaded") ? 0 : 1;
                                                })))
                                .then(CommandManager.literal("perf").executes(context -> {
                                    context.getSource().sendFeedback(() -> Text.literal(SHOW_MANAGER.performanceSummary()), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("clear_entities").executes(context -> {
                                    int count = SHOW_MANAGER.clearFallingEntities();
                                    context.getSource().sendFeedback(() -> Text.literal("Cleared " + count + " falling block entities"), true);
                                    return 1;
                                })))
        ));
    }

    private static int load(ServerCommandSource source, String file) {
        try {
            String id = SHOW_MANAGER.load(file);
            source.sendFeedback(() -> Text.literal("Loaded piano show " + id), true);
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("Unable to load show: " + exception.getMessage()));
            return 0;
        }
    }

    private static int play(ServerCommandSource source, double speed) {
        try {
            SHOW_MANAGER.setWorld(source.getWorld());
            if (!SHOW_MANAGER.hasStageOrigin()) SHOW_MANAGER.setOrigin(BlockPos.ofFloored(source.getPosition()));
            SHOW_MANAGER.start(speed);
            source.sendFeedback(() -> Text.literal("Piano show playing at " + speed + "x"), true);
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("Unable to play show: " + exception.getMessage()));
            return 0;
        }
    }

    private static int build(ServerCommandSource source, BlockPos position) {
        try {
            if (!SHOW_MANAGER.isLoaded()) throw new IllegalStateException("load a show first");
            SHOW_MANAGER.buildKeyboard(source.getWorld(), position, 21, 108);
            source.sendFeedback(() -> Text.literal("Built 88-key piano at " + position.toShortString()), true);
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("Unable to build piano: " + exception.getMessage()));
            return 0;
        }
    }
}
