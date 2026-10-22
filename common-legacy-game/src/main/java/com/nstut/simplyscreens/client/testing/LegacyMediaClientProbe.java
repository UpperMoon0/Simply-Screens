package com.nstut.simplyscreens.client.testing;

import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.ScreenTileLayout;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import com.nstut.simplyscreens.testing.LegacyMediaProbePacket;
import com.nstut.simplyscreens.testing.LegacyMediaProbeProtocol;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Real-client media probe. Does not connect, change account eligibility, or change permissions. */
public final class LegacyMediaClientProbe {
    private static boolean initialized, requested, uploaded, cleanupRequested, complete, failed;
    private static UUID session, display, playerId;
    private static BlockPos pos;
    private static Object world;
    private static ResourceLocation registeredTexture;
    private static long started;
    private static float savedYaw, savedPitch;
    private static final LegacyMediaProbeProtocol.Evidence evidence = new LegacyMediaProbeProtocol.Evidence();
    private LegacyMediaClientProbe() { }
    public static void initialize() {
        if (!LegacyMediaProbeProtocol.ENABLED || initialized) return;
        initialized = true;
        NetworkManager.registerReceiver(NetworkManager.s2c(), LegacyMediaProbePacket.S2C, (buf, ctx) -> {
            if (!LegacyMediaProbeProtocol.ENABLED) return;
            LegacyMediaProbePacket packet = LegacyMediaProbePacket.read(buf, false);
            ctx.queue(() -> receive(packet));
        });
    }
    /** Returns true only after all real-media gates and identity-scoped server cleanup. */
    public static boolean tick(Minecraft client) {
        try { return tickActive(client); }
        catch (RuntimeException failure) { throw abort(failure); }
    }
    /** Check an existing probe before outer UI handlers can return on world loss. */
    public static void validateActiveLifecycle(Minecraft client) {
        if (!LegacyMediaProbeProtocol.ENABLED || !requested || complete || failed) return;
        try { requireLive(client); }
        catch (RuntimeException failure) { throw abort(failure); }
    }
    private static boolean tickActive(Minecraft client) {
        if (!LegacyMediaProbeProtocol.ENABLED) return true;
        if (failed) throw new IllegalStateException(LegacyMediaProbeProtocol.FAIL + " client already failed");
        if (complete) return true;
        if (!requested) {
            if (client.player == null || client.level == null) return false;
            initialize(); requested = true; started = System.nanoTime(); world = client.level;
            savedYaw = client.player.getYRot(); savedPitch = client.player.getXRot();
            session = UUID.randomUUID(); playerId = client.player.getUUID();
            PacketRegistries.sendToServer(new LegacyMediaProbePacket(LegacyMediaProbePacket.REQUEST, session, null, null, null));
            return false;
        }
        requireLive(client);
        if (pos == null || cleanupRequested) return false;
        if (!(client.level.getBlockEntity(pos) instanceof ScreenBlockEntity)) return false;
        ScreenBlockEntity screen = (ScreenBlockEntity) client.level.getBlockEntity(pos);
        // Initial native block-entity data may arrive after the control acknowledgment.
        if (!display.equals(screen.getDisplayId())) return false;
        aim(client);
        if (!uploaded) {
            if (screen.getResolvedImageId() != null) fail("fixture was not empty before upload");
            byte[] png = LegacyMediaProbeProtocol.png();
            if (png.length > com.nstut.packetweave.api.ChunkedTransfer.CHUNK_BYTES) fail("owned fixture no longer fits one normal chunk");
            uploaded = true;
            PacketRegistries.sendToServer(new UploadImageChunkC2SPacket(pos, UUID.randomUUID(), 0, 1, png,
                    LegacyMediaProbeProtocol.imageName(session) + ".png"));
            return false;
        }
        UUID image = screen.getResolvedImageId();
        if (image == null) return false;
        // Production lookup requests the normal C2S download, whose receiver writes/decodes the PNG.
        ResourceLocation location = ClientImageManager.getTextureLocation(image);
        DynamicTexture texture = ClientImageManager.getImageTexture(image);
        if (location != null && texture != null && evidence.downloaded() && !evidence.textured()) {
            NativeImage pixels = texture.getPixels();
            if (pixels == null) fail("decoded native pixels missing");
            boolean matches = pixels.getWidth() == LegacyMediaProbeProtocol.SIZE && pixels.getHeight() == LegacyMediaProbeProtocol.SIZE;
            if (matches) for (int y = 0; y < LegacyMediaProbeProtocol.SIZE; y++) for (int x = 0; x < LegacyMediaProbeProtocol.SIZE; x++)
                if (pixels.getPixelRGBA(x, y) != LegacyMediaProbeProtocol.abgr(x, y)) matches = false;
            evidence.texture(image, pixels.getWidth(), pixels.getHeight(), matches, texture.getId() > 0);
            registeredTexture = location;
        }
        if (evidence.passed()) {
            cleanupRequested = true;
            PacketRegistries.sendToServer(new LegacyMediaProbePacket(LegacyMediaProbePacket.CLEANUP, session, pos, display, evidence.image()));
        }
        return false;
    }
    private static void receive(LegacyMediaProbePacket packet) {
        try { receiveActive(packet); }
        catch (RuntimeException failure) { throw abort(failure); }
    }
    private static void receiveActive(LegacyMediaProbePacket packet) {
        if (!LegacyMediaProbeProtocol.ENABLED || !requested || complete || failed || !session.equals(packet.session)) return;
        requireLive(Minecraft.getInstance());
        if (packet.phase == LegacyMediaProbePacket.FAILED) fail("server rejected media fixture or lifecycle");
        if (packet.phase == LegacyMediaProbePacket.FIXTURE) {
            if (pos != null || packet.pos == null || packet.display == null || packet.image != null) fail("invalid fixture acknowledgment");
            pos = packet.pos; display = packet.display;
            return;
        }
        if (pos == null || !pos.equals(packet.pos) || !display.equals(packet.display)) fail("server fixture identity mismatch");
        if (packet.phase == LegacyMediaProbePacket.VERIFIED) {
            if (!uploaded) fail("server upload evidence preceded upload request");
            evidence.server(packet.image);
        } else if (packet.phase == LegacyMediaProbePacket.CLEANED) {
            if (!cleanupRequested || !evidence.passed() || !evidence.image().equals(packet.image)) fail("cleanup preceded media evidence");
            releaseOwnedClientState();
            complete = true;
            SimplyScreens.LOGGER.info(LegacyMediaProbeProtocol.CLIENT_PASS + " session=" + session + " image=" + packet.image
                    + " download=true decodedPixels=true registeredTexture=true geometrySubmitted=true ownedCleanup=true framebufferPixels=not_tested");
        } else fail("unexpected server probe phase");
    }
    /** Called only after production download assembly; does not consume or replace its bytes. */
    public static void downloaded(UUID image, String extension, byte[] bytes) {
        try { downloadedActive(image, extension, bytes); }
        catch (RuntimeException failure) { throw abort(failure); }
    }
    private static void downloadedActive(UUID image, String extension, byte[] bytes) {
        if (!LegacyMediaProbeProtocol.ENABLED || !requested || !uploaded || complete || failed || cleanupRequested || pos == null) return;
        Minecraft client = Minecraft.getInstance();
        requireLive(client);
        if (!(client.level.getBlockEntity(pos) instanceof ScreenBlockEntity)) return;
        ScreenBlockEntity screen = (ScreenBlockEntity) client.level.getBlockEntity(pos);
        if (display.equals(screen.getDisplayId()) && image.equals(screen.getResolvedImageId())) evidence.download(image, extension, bytes);
    }
    /** Opt-in native renderer hook AFTER all four vertices were submitted, never a synthetic render. */
    public static void geometrySubmitted(ScreenBlockEntity screen, UUID image, ResourceLocation texture, ScreenTileLayout.Tile tile) {
        try { geometryActive(screen, image, texture, tile); }
        catch (RuntimeException failure) { throw abort(failure); }
    }
    private static void geometryActive(ScreenBlockEntity screen, UUID image, ResourceLocation texture, ScreenTileLayout.Tile tile) {
        if (!LegacyMediaProbeProtocol.ENABLED || !requested || complete || failed || cleanupRequested || !evidence.textured()) return;
        requireLive(Minecraft.getInstance());
        if (pos.equals(screen.getBlockPos()) && display.equals(screen.getDisplayId()) && registeredTexture.equals(texture)
                && image.equals(screen.getResolvedImageId()) && !tile.isEmpty()
                && Float.isFinite(tile.minX()) && Float.isFinite(tile.minY()) && Float.isFinite(tile.maxX()) && Float.isFinite(tile.maxY()))
            evidence.geometry(image);
    }
    private static void aim(Minecraft client) {
        double dx = pos.getX() + .5 - client.player.getX();
        double dy = pos.getY() + .5 - client.player.getEyeY();
        double dz = pos.getZ() + .5 - client.player.getZ();
        client.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
        client.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }
    private static void requireLive(Minecraft client) {
        LegacyMediaProbeProtocol.requireSession(world, playerId, System.nanoTime() - started,
                client.level, client.player == null ? null : client.player.getUUID());
    }
    private static void releaseOwnedClientState() {
        if (evidence.image() != null) ClientImageManager.invalidateImage(evidence.image());
        registeredTexture = null;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.level == world && playerId.equals(client.player.getUUID())) {
            client.player.setYRot(savedYaw);
            client.player.setXRot(savedPitch);
        }
    }
    private static IllegalStateException abort(RuntimeException cause) {
        if (!failed) {
            failed = true;
            SimplyScreens.LOGGER.error(LegacyMediaProbeProtocol.FAIL + " client", cause);
            try { releaseOwnedClientState(); }
            catch (RuntimeException cleanupFailure) { SimplyScreens.LOGGER.error("Owned media probe client cleanup failed", cleanupFailure); }
        }
        try { evidence.fail(cause.getMessage()); }
        catch (IllegalStateException closed) { return closed; }
        throw new AssertionError("Evidence failure did not throw");
    }
    private static void fail(String reason) {
        throw new IllegalStateException(LegacyMediaProbeProtocol.FAIL + " client " + reason);
    }
}
