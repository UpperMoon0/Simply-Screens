package com.nstut.simplyscreens.testing;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import dev.architectury.networking.NetworkManager;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.Arrays;
import java.util.UUID;

/** One opt-in, authenticated player's air-only fixture in an isolated native test world. */
public final class LegacyMediaServerProbe {
    private static Fixture fixture, retired;
    private static boolean initialized, used;
    private LegacyMediaServerProbe() { }
    public static void initialize() {
        if (!LegacyMediaProbeProtocol.ENABLED || initialized) return;
        initialized = true;
        NetworkManager.registerReceiver(NetworkManager.c2s(), LegacyMediaProbePacket.C2S, (buf, ctx) -> {
            if (!LegacyMediaProbeProtocol.ENABLED) return;
            LegacyMediaProbePacket packet = LegacyMediaProbePacket.read(buf, true);
            ctx.queue(() -> receive((ServerPlayer) ctx.getPlayer(), packet));
        });
        TickEvent.SERVER_POST.register(LegacyMediaServerProbe::tick);
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            if (fixture != null) { cleanup(server, fixture); retired = fixture; }
            fixture = null;
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> { fixture = null; retired = null; used = false; });
    }
    private static void receive(ServerPlayer player, LegacyMediaProbePacket packet) {
        if (!LegacyMediaProbeProtocol.ENABLED || player == null || player.getServer() == null
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        if (packet.phase == LegacyMediaProbePacket.REQUEST) {
            if (used || packet.pos != null || packet.display != null || packet.image != null) return;
            used = true;
            try {
                fixture = place(player, packet.session);
                reply(player, LegacyMediaProbePacket.FIXTURE, fixture);
            } catch (RuntimeException failure) {
                SimplyScreens.LOGGER.error(LegacyMediaProbeProtocol.FAIL + " server fixture", failure);
                PacketRegistries.sendToPlayer(player, new LegacyMediaProbePacket(LegacyMediaProbePacket.FAILED, packet.session, null, null, null));
            }
            return;
        }
        Fixture active = fixture;
        if (active == null || !active.owner.equals(player.getUUID()) || !active.session.equals(packet.session)
                || player.serverLevel() != active.level || !active.pos.equals(packet.pos)
                || !active.display.equals(packet.display) || !java.util.Objects.equals(active.image, packet.image)) return;
        if (expired(active) || !ownedBlock(active)) {
            fail(player.getServer(), active, player, "deadline or fixture identity changed before control acknowledgment"); return;
        }
        if (packet.phase == LegacyMediaProbePacket.CLEANUP && active.verified && active.image != null) {
            boolean cleaned = cleanup(player.getServer(), active);
            reply(player, cleaned ? LegacyMediaProbePacket.CLEANED : LegacyMediaProbePacket.FAILED, active);
            retired = active; fixture = null;
        }
    }
    private static Fixture place(ServerPlayer player, UUID session) {
        ServerLevel level = player.serverLevel();
        for (ImageMetadata metadata : ServerImageManager.getImageListForPlayer(player.getServer(), player.getUUID().toString()))
            if (LegacyMediaProbeProtocol.imageName(session).equals(metadata.getName()))
                throw new IllegalStateException("Probe session already has stored media");
        BlockPos base = player.blockPosition();
        for (Direction direction : new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}) {
            BlockPos pos = base.relative(direction, 3).relative(Direction.UP);
            if (!level.hasChunkAt(pos) || !level.isEmptyBlock(pos) || !player.mayInteract(level, pos)) continue;
            boolean isolated = true;
            for (Direction neighbor : Direction.values()) {
                BlockPos adjacent = pos.relative(neighbor);
                if (!level.hasChunkAt(adjacent) || level.getBlockEntity(adjacent) instanceof ScreenBlockEntity) isolated = false;
            }
            if (!isolated) continue;
            if (!level.setBlock(pos, BlockRegistries.SCREEN.get().defaultBlockState()
                    .setValue(ScreenBlock.FACING, direction.getOpposite()), 3)) continue;
            if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity)) {
                // This call placed the air-only block on the same server thread.
                if (level.getBlockState(pos).getBlock() == BlockRegistries.SCREEN.get()) level.removeBlock(pos, false);
                throw new IllegalStateException("Fixture has no block entity");
            }
            ScreenBlockEntity screen = (ScreenBlockEntity) level.getBlockEntity(pos);
            try { screen.claimPlaced(player.getUUID()); }
            catch (RuntimeException failure) {
                if (level.getBlockEntity(pos) == screen) level.removeBlock(pos, false);
                throw failure;
            }
            Fixture created = new Fixture(session, player.getUUID(), level, pos, screen.getDisplayId());
            fixture = created;
            level.sendBlockUpdated(pos, screen.getBlockState(), screen.getBlockState(), 3);
            if (!ScreenPacketSecurity.canModify(player, pos)) {
                cleanup(player.getServer(), created);
                fixture = null;
                throw new IllegalStateException("Real screen permission rejected fixture; permissions were not changed");
            }
            return created;
        }
        throw new IllegalStateException("No permitted isolated air block near the real player");
    }
    private static void tick(MinecraftServer server) {
        Fixture active = fixture;
        if (!LegacyMediaProbeProtocol.ENABLED || active == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(active.owner);
        if (expired(active)
                || player == null || player.serverLevel() != active.level) {
            fail(server, active, player, "deadline or player/world changed"); return;
        }
        if (!ownedBlock(active)) { fail(server, active, player, "fixture identity changed"); return; }
        if (active.verified) return;
        ScreenBlockEntity screen = (ScreenBlockEntity) active.level.getBlockEntity(active.pos);
        UUID image = screen.getResolvedImageId();
        if (image == null || active.image == null) return;
        if (!active.image.equals(image) || !ownedImage(server, active, image) || !ScreenPacketSecurity.canModify(player, active.pos)) {
            fail(server, active, player, "completed upload identity/content/permission mismatch"); return;
        }
        active.verified = true;
        SimplyScreens.LOGGER.info(LegacyMediaProbeProtocol.SERVER_PASS + " session=" + active.session + " image=" + image
                + " position=" + active.pos + " uploadStored=true ownership=true");
        reply(player, LegacyMediaProbePacket.VERIFIED, active);
    }
    /** Observe only the existing production upload completion, on its server task. */
    public static void uploadStored(ServerPlayer player, ServerLevel uploadLevel, BlockPos pos, UUID image, String originalName) {
        if (!LegacyMediaProbeProtocol.ENABLED || player == null || player.getServer() == null) return;
        Fixture active = fixture != null ? fixture : retired;
        if (active == null || active.level != uploadLevel || !active.owner.equals(player.getUUID()) || !active.pos.equals(pos)
                || !((LegacyMediaProbeProtocol.imageName(active.session) + ".png").equals(originalName))
                || !ownedImage(player.getServer(), active, image)) return;
        if (active != retired && (expired(active) || player.serverLevel() != active.level)) {
            if (active.image == null) active.image = image;
            else if (!active.image.equals(image)) ServerImageManager.deleteImage(player.getServer(), image, active.owner.toString());
            fail(player.getServer(), active, player, "deadline or player/world changed before upload completion"); return;
        }
        if (active == retired) {
            // A timed-out asynchronous upload may finish after its owned block was removed.
            ServerImageManager.deleteImage(player.getServer(), image, active.owner.toString());
        } else if (active.image == null) active.image = image;
        else if (!active.image.equals(image)) {
            ServerImageManager.deleteImage(player.getServer(), image, active.owner.toString());
            fail(player.getServer(), active, player, "unexpected duplicate fixture upload");
        }
    }
    private static boolean expired(Fixture active) {
        return System.nanoTime() - active.started > LegacyMediaProbeProtocol.TIMEOUT_NANOS;
    }
    private static boolean ownedBlock(Fixture active) {
        return active.level.hasChunkAt(active.pos) && active.level.getBlockEntity(active.pos) instanceof ScreenBlockEntity
                && active.display.equals(((ScreenBlockEntity) active.level.getBlockEntity(active.pos)).getDisplayId());
    }
    private static boolean ownedImage(MinecraftServer server, Fixture active, UUID image) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, image);
        return metadata != null && active.owner.toString().equals(metadata.getOwnerUUID())
                && LegacyMediaProbeProtocol.imageName(active.session).equals(metadata.getName())
                && "png".equals(metadata.getExtension())
                && Arrays.equals(LegacyMediaProbeProtocol.png(), ServerImageManager.getImageData(server, image));
    }
    private static boolean cleanup(MinecraftServer server, Fixture active) {
        boolean clean = true;
        if (active.image != null) {
            if (ownedImage(server, active, active.image))
                clean = ServerImageManager.deleteImage(server, active.image, active.owner.toString());
            else clean = false; // Do not delete altered or unverified content.
        }
        if (ownedBlock(active)) clean &= active.level.removeBlock(active.pos, false);
        else clean = false; // Never remove a replacement or unrelated screen.
        return clean;
    }
    private static void fail(MinecraftServer server, Fixture active, ServerPlayer player, String reason) {
        SimplyScreens.LOGGER.error(LegacyMediaProbeProtocol.FAIL + " server " + reason);
        cleanup(server, active);
        if (player != null) reply(player, LegacyMediaProbePacket.FAILED, active);
        retired = active; fixture = null;
    }
    private static void reply(ServerPlayer player, int phase, Fixture active) {
        PacketRegistries.sendToPlayer(player, new LegacyMediaProbePacket(phase, active.session, active.pos, active.display, active.image));
    }
    private static final class Fixture {
        final UUID session, owner, display;
        final ServerLevel level;
        final BlockPos pos;
        final long started = System.nanoTime();
        UUID image;
        boolean verified;
        Fixture(UUID session, UUID owner, ServerLevel level, BlockPos pos, UUID display) {
            this.session = session; this.owner = owner; this.level = level; this.pos = pos; this.display = display;
        }
    }
}
