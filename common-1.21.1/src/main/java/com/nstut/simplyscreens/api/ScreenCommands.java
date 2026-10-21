package com.nstut.simplyscreens.api;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenNetworkService.Actor;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ScreenAccessService;
import com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Operator-only admin entry point. Every mutation uses the same actor-aware control service. */
public final class ScreenCommands {
    private static final int PAGE_SIZE = 10;
    private ScreenCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("simplyscreens").requires(ScreenCommands::admin)
            .executes(ctx -> success(ctx, "Use /screens list, info, set, clear, play, pause, resume, stop, next, previous, restart, media, display or reconcile."))
            .then(Commands.literal("list").executes(ctx -> list(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 10000))
                    .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            .then(Commands.literal("info").then(networkArg().executes(ScreenCommands::info)))
            .then(Commands.literal("status").then(networkArg().executes(ScreenCommands::info)))
            .then(Commands.literal("set").then(networkArg()
                .then(Commands.argument("uuid", StringArgumentType.word()).executes(ScreenCommands::set))))
            .then(Commands.literal("clear").then(networkArg().executes(ctx -> setImage(ctx, null))))
            .then(Commands.literal("play").then(networkArg()
                .then(Commands.argument("owner", StringArgumentType.word())
                    .then(Commands.argument("playlist", StringArgumentType.word())
                        .executes(ScreenCommands::play)))))
            .then(Commands.literal("pause").then(networkArg().executes(ctx -> transport(ctx, "PAUSE"))))
            .then(Commands.literal("resume").then(networkArg().executes(ctx -> transport(ctx, "RESUME"))))
            .then(Commands.literal("stop").then(networkArg().executes(ctx -> transport(ctx, "STOP"))))
            .then(Commands.literal("next").then(networkArg().executes(ctx -> transport(ctx, "NEXT"))))
            .then(Commands.literal("previous").then(networkArg().executes(ctx -> transport(ctx, "PREVIOUS"))))
            .then(Commands.literal("restart").then(networkArg().executes(ctx -> transport(ctx, "RESTART"))))
            .then(Commands.literal("reconcile").then(networkArg().executes(ScreenCommands::reconcile)))
            .then(Commands.literal("media").executes(ctx -> media(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 10000))
                    .executes(ctx -> media(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
            .then(Commands.literal("display")
                .then(Commands.argument("dimension", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (var level : ctx.getSource().getServer().getAllLevels())
                            builder.suggest(level.dimension().location().toString());
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("x", IntegerArgumentType.integer(-30000000, 30000000))
                        .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
                            .then(Commands.argument("z", IntegerArgumentType.integer(-30000000, 30000000))
                                .executes(ScreenCommands::display)))))));
        dispatcher.register(Commands.literal("screens").requires(ScreenCommands::admin)
                .redirect(dispatcher.getRoot().getChild("simplyscreens")));
    }

    private static boolean admin(CommandSourceStack source) {
        if (source.getServer() == null) return false;
        return source.hasPermission(2);
    }

    private static Actor acting(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? new Actor(null, true) : ScreenAccessService.actor(player);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> networkArg() {
        return Commands.argument("network", StringArgumentType.string()).suggests((ctx, builder) -> {
            for (String key : ScreenRegistry.networks().keys())
                builder.suggest(StringArgumentType.escapeIfRequired(key.startsWith("network/") ? key.substring("network/".length()) : key));
            return builder.buildFuture();
        });
    }

    private static String network(CommandContext<CommandSourceStack> ctx) {
        try {
            String key = ScreenTarget.parse(StringArgumentType.getString(ctx, "network"));
            if (ScreenRegistry.networks().snapshot(key) != null) return key;
        } catch (RuntimeException invalid) { /* Empty or invalid identifier. */ }
        ctx.getSource().sendFailure(Component.literal("Unknown Screen ID. Use /screens list."));
        return null;
    }

    private static int success(CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int mutation(CommandContext<CommandSourceStack> ctx, String key, String action, boolean done) {
        if (!done) {
            ctx.getSource().sendFailure(Component.literal("Screen operation rejected. Check the ID, permission, media ownership and current playback state."));
            return 0;
        }
        String report = action + " " + key;
        SimplyScreens.LOGGER.info("Screen admin operation by {}: {}", ctx.getSource().getDisplayName().getString(), report);
        return success(ctx, report);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, int page) {
        var all = ScreenApi.control().list(acting(ctx.getSource()));
        long start = (long) (page - 1) * PAGE_SIZE;
        if (start >= all.size() && !all.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Page out of range; " + all.size() + " registered states."));
            return 0;
        }
        success(ctx, "Screen networks/displays (" + all.size() + " total), page " + page + ":");
        for (int i = (int) start; i < Math.min(all.size(), start + PAGE_SIZE); i++) {
            var s = all.get(i);
            success(ctx, "  " + s.key() + " — " + s.playbackStatus() + ", image=" + s.image());
        }
        return all.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        String key = network(ctx);
        if (key == null) return 0;
        var s = ScreenApi.control().status(key, acting(ctx.getSource()));
        if (s == null) {
            ctx.getSource().sendFailure(Component.literal("Screen state unavailable."));
            return 0;
        }
        return success(ctx, key + "\n  owner=" + s.owner() + ", image=" + s.image()
            + "\n  view=" + s.viewPolicy() + ", control=" + s.controlPolicy()
            + "\n  network revision=" + s.networkRevision() + ", playback=" + s.playbackStatus()
            + "\n  slide=" + s.slideIndex() + ", elapsed=" + s.offsetMillis()
            + "ms, playback revision=" + s.playbackRevision());
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        try { return setImage(ctx, UUID.fromString(StringArgumentType.getString(ctx, "uuid"))); }
        catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("Invalid image UUID."));
            return 0;
        }
    }

    private static int setImage(CommandContext<CommandSourceStack> ctx, UUID id) {
        String key = network(ctx);
        if (key == null) return 0;
        Actor actor = acting(ctx.getSource());
        boolean ok = ScreenApi.control().select(actor, key, id,
                image -> ScreenApi.canUseImage(ctx.getSource().getServer(), actor, image));
        if (ok) ScreenAccessService.refreshLoadedContent(key);
        return mutation(ctx, key, id == null ? "Clear image" : "Set image " + id, ok);
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        String key = network(ctx);
        if (key == null) return 0;
        UUID owner, playlist;
        try {
            owner = UUID.fromString(StringArgumentType.getString(ctx, "owner"));
            playlist = UUID.fromString(StringArgumentType.getString(ctx, "playlist"));
        } catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("Use UUIDs for owner and playlist."));
            return 0;
        }
        Actor actor = acting(ctx.getSource());
        boolean ok = ScreenApi.control().play(actor, key, owner, playlist,
                image -> ScreenApi.canUseImage(ctx.getSource().getServer(), actor, image));
        if (ok) refreshPlayback(key);
        return mutation(ctx, key, "Play " + playlist, ok);
    }

    private static int transport(CommandContext<CommandSourceStack> ctx, String action) {
        String key = network(ctx);
        if (key == null) return 0;
        boolean ok = ScreenApi.control().transport(acting(ctx.getSource()), key, action);
        if (ok) refreshPlayback(key);
        return mutation(ctx, key, action, ok);
    }

    private static void refreshPlayback(String key) {
        var service = ScreenPlaylistRuntime.service();
        if (service != null) ScreenPlaylistRuntime.reconcile(key);
    }

    private static int reconcile(CommandContext<CommandSourceStack> ctx) {
        String key = network(ctx);
        if (key == null) return 0;
        var service = ScreenPlaylistRuntime.service();
        if (service != null && service.managesContent(key)) refreshPlayback(key);
        else ScreenAccessService.refreshLoadedContent(key);
        return mutation(ctx, key, "Reconcile loaded displays", true);
    }

    private static int media(CommandContext<CommandSourceStack> ctx, int page) {
        List<ImageMetadata> all = new ArrayList<>(ServerImageManager.getImageList(ctx.getSource().getServer()));
        all.sort(Comparator.comparing(ImageMetadata::getName).thenComparing(ImageMetadata::getId));
        long start = (long) (page - 1) * PAGE_SIZE;
        if (start >= all.size() && !all.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Page out of range; " + all.size() + " images."));
            return 0;
        }
        success(ctx, "Stored images (" + all.size() + " total), page " + page + ":");
        for (int i = (int) start; i < Math.min(all.size(), start + PAGE_SIZE); i++) {
            ImageMetadata item = all.get(i);
            success(ctx, "  " + item.getId() + " — " + item.getName() + " (owner " + item.getOwnerUUID() + ")");
        }
        return all.size();
    }

    private static int display(CommandContext<CommandSourceStack> ctx) {
        String dimension = StringArgumentType.getString(ctx, "dimension");
        BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
        for (var level : ctx.getSource().getServer().getAllLevels()) {
            if (!level.dimension().location().toString().equals(dimension)) continue;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) break;
            if (chunk.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
                String key = screen.contentKey();
                var state = ScreenRegistry.networks().snapshot(key);
                return success(ctx, "Display " + dimension + " " + pos.toShortString()
                    + "\n  key=" + key + ", display=" + screen.getDisplayId()
                    + "\n  image=" + screen.getResolvedImageId() + ", override=" + screen.isLocalImageOverride()
                    + "\n  size=" + screen.getScreenWidth() + "x" + screen.getScreenHeight()
                    + ", owner=" + (state == null ? null : state.owner()));
            }
            break;
        }
        ctx.getSource().sendFailure(Component.literal("Display absent or chunk unloaded; chunks were not force-loaded."));
        return 0;
    }
}
