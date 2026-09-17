package com.nstut.simplyscreens.testing.visual;

import com.google.gson.*;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Injected only into disposable verification builds; never a production source set. */
public final class VisualServer {
    private static final Path DIR = Path.of(System.getenv("SS_VISUAL_DIR"));
    private static final Gson JSON = new Gson();
    private static final List<BlockPos> placed = new ArrayList<>();
    private static String current = "";
    private static UUID image;

    public static void tick(MinecraftServer server) {
        try {
            if (!Files.exists(DIR.resolve("request.json")) || server.getPlayerList().getPlayers().isEmpty()) return;
            JsonObject request = JsonParser.parseString(Files.readString(DIR.resolve("request.json"))).getAsJsonObject();
            String id = request.get("id").getAsString();
            if (id.equals(current)) return;
            current = id;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            Config.SCREEN_TICK_RATE = 72000;
            Config.VIEW_DISTANCE = 512;
            player.setGameMode(GameType.SPECTATOR);
            if (image == null) {
                image = ServerImageManager.saveImage(server, "visual-fixture.png",
                        Files.readAllBytes(DIR.resolve("fixture.png")), "image/png");
                if (image == null) throw new IllegalStateException("fixture image rejected by production image pipeline");
            }
            for (BlockPos pos : placed) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            placed.clear();
            Direction facing = Direction.valueOf(request.get("facing").getAsString());
            Direction width = switch (facing) {
                case NORTH, UP, DOWN -> Direction.WEST;
                case SOUTH -> Direction.EAST;
                case WEST -> Direction.SOUTH;
                case EAST -> Direction.NORTH;
            };
            Direction height = facing.getAxis().isHorizontal() ? Direction.UP
                    : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
            int size = request.get("size").getAsInt();
            BlockPos anchor = new BlockPos(0, 128, 0);
            JsonArray boxes = new JsonArray();
            for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
                BlockPos pos = anchor.relative(width, x).relative(height, y);
                level.setBlockAndUpdate(pos, BlockRegistries.SCREEN.get().defaultBlockState().setValue(ScreenBlock.FACING, facing));
                placed.add(pos);
            }
            for (BlockPos pos : List.copyOf(placed)) {
                if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen))
                    throw new IllegalStateException("missing server screen " + pos);
                screen.updateScreen(image, size, size, anchor, false);
            }
            if (request.get("occluded").getAsBoolean()) {
                for (int x = 0; x < size / 2; x++) for (int y = 0; y < size; y++) {
                    BlockPos pos = anchor.relative(width, x).relative(height, y).relative(facing);
                    level.setBlockAndUpdate(pos, Blocks.RED_CONCRETE.defaultBlockState());
                    placed.add(pos);
                    boxes.add(JSON.toJsonTree(new double[]{pos.getX(), pos.getY(), pos.getZ(), pos.getX()+1, pos.getY()+1, pos.getZ()+1}));
                }
            }
            Vec3 normal = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
            Vec3 right = new Vec3(width.getStepX(), width.getStepY(), width.getStepZ());
            Vec3 up = new Vec3(height.getStepX(), height.getStepY(), height.getStepZ());
            Vec3 center = Vec3.atCenterOf(anchor).add(right.scale((size-1)/2.0)).add(up.scale((size-1)/2.0)).add(normal.scale(0.5));
            double angle = Math.toRadians(request.get("angle").getAsDouble());
            double distance = request.get("distance").getAsDouble();
            Vec3 eye = center.add(normal.scale(distance*Math.cos(angle))).add(right.scale(distance*Math.sin(angle)));
            Vec3 look = center.subtract(eye).normalize();
            float yaw = (float)Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float)-Math.toDegrees(Math.asin(look.y));
            // Teleport through the real server connection; the client must acknowledge the fixture.
            __TELEPORT__
            JsonObject ready = request.deepCopy();
            ready.addProperty("image", image.toString());
            ready.addProperty("yaw", yaw);
            ready.addProperty("pitch", pitch);
            ready.add("center", JSON.toJsonTree(new double[]{center.x, center.y, center.z}));
            ready.add("normal", JSON.toJsonTree(new double[]{normal.x, normal.y, normal.z}));
            ready.add("right", JSON.toJsonTree(new double[]{right.x, right.y, right.z}));
            ready.add("up", JSON.toJsonTree(new double[]{up.x, up.y, up.z}));
            ready.add("eye", JSON.toJsonTree(new double[]{eye.x, eye.y, eye.z}));
            ready.add("occluders", boxes);
            write("server-ready.json", ready);
        } catch (Throwable failure) {
            try { Files.writeString(DIR.resolve("server-fail.txt"), failure.toString()); } catch (Exception ignored) { }
        }
    }

    private static void write(String name, JsonObject value) throws Exception {
        Path tmp = DIR.resolve(name+".tmp");
        Files.writeString(tmp, JSON.toJson(value));
        Files.move(tmp, DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
}
