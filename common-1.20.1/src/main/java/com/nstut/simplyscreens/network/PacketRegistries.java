package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class PacketRegistries {
    private static final ResourceLocation UPDATE_SELECTED = id("update_screen_selected_image");
    private static final ResourceLocation UPDATE_SCREEN = id("update_screen");
    private static final ResourceLocation UPDATE_ASPECT = id("update_screen_aspect_ratio");
    private static final ResourceLocation REQUEST_DOWNLOAD = id("request_image_download");
    private static final ResourceLocation REQUEST_LIST = id("request_image_list");
    private static final ResourceLocation UPDATE_LIST = id("update_image_list");
    private static final ResourceLocation UPLOAD_CHUNK = id("upload_image_chunk");
    private static final ResourceLocation DOWNLOAD_CHUNK = id("image_download_chunk");
    private static final ResourceLocation UPDATE_ID = id("update_screen_id");
    private static final ResourceLocation DOWNLOAD_URL = id("download_image_from_url");
    private static final ResourceLocation REMOVE_IMAGE = id("remove_image");
    private static final ResourceLocation INVALIDATE_IMAGE = id("invalidate_image");
    private static final ResourceLocation CONFIG_SYNC = id("server_config_sync");

    private PacketRegistries() { }

    private static ResourceLocation id(String path) { return new ResourceLocation(SimplyScreens.MOD_ID, path); }

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.c2s(), UPDATE_SELECTED, (buf, ctx) -> new UpdateScreenSelectedImageC2SPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), UPDATE_ASPECT, (buf, ctx) -> new UpdateScreenAspectRatioC2SPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), REQUEST_DOWNLOAD, (buf, ctx) -> new RequestImageDownloadC2SPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), REQUEST_LIST, (buf, ctx) -> RequestImageListC2SPacket.apply(RequestImageListC2SPacket.read(buf), () -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), UPLOAD_CHUNK, (buf, ctx) -> UploadImageChunkC2SPacket.apply(UploadImageChunkC2SPacket.read(buf), () -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), UPDATE_ID, (buf, ctx) -> new UpdateScreenIdC2SPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), DOWNLOAD_URL, (buf, ctx) -> new DownloadImageFromUrlC2SPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.c2s(), REMOVE_IMAGE, (buf, ctx) -> RemoveImageC2SPacket.apply(RemoveImageC2SPacket.read(buf), () -> ctx));
    }

    public static void registerS2CPackets() {
        NetworkManager.registerReceiver(NetworkManager.s2c(), UPDATE_SCREEN, (buf, ctx) -> new UpdateScreenS2CPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.s2c(), UPDATE_LIST, (buf, ctx) -> new UpdateImageListS2CPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.s2c(), DOWNLOAD_CHUNK, (buf, ctx) -> new ImageDownloadChunkS2CPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.s2c(), INVALIDATE_IMAGE, (buf, ctx) -> new InvalidateImageS2CPacket(buf).handle(() -> ctx));
        NetworkManager.registerReceiver(NetworkManager.s2c(), CONFIG_SYNC, (buf, ctx) -> new ServerConfigSyncS2CPacket(buf).handle(() -> ctx));
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) { send(NetworkManager.s2c(), player, packet); }
    public static void sendToServer(Object packet) { send(NetworkManager.c2s(), null, packet); }

    private static void send(NetworkManager.Side side, ServerPlayer player, Object packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        write(packet, buf);
        if (side == NetworkManager.s2c()) NetworkManager.sendToPlayer(player, packetId(packet), buf);
        else NetworkManager.sendToServer(packetId(packet), buf);
    }

    private static ResourceLocation packetId(Object packet) {
        if (packet instanceof UpdateScreenSelectedImageC2SPacket) return UPDATE_SELECTED;
        if (packet instanceof UpdateScreenS2CPacket) return UPDATE_SCREEN;
        if (packet instanceof UpdateScreenAspectRatioC2SPacket) return UPDATE_ASPECT;
        if (packet instanceof RequestImageDownloadC2SPacket) return REQUEST_DOWNLOAD;
        if (packet instanceof RequestImageListC2SPacket) return REQUEST_LIST;
        if (packet instanceof UpdateImageListS2CPacket) return UPDATE_LIST;
        if (packet instanceof UploadImageChunkC2SPacket) return UPLOAD_CHUNK;
        if (packet instanceof ImageDownloadChunkS2CPacket) return DOWNLOAD_CHUNK;
        if (packet instanceof UpdateScreenIdC2SPacket) return UPDATE_ID;
        if (packet instanceof DownloadImageFromUrlC2SPacket) return DOWNLOAD_URL;
        if (packet instanceof RemoveImageC2SPacket) return REMOVE_IMAGE;
        if (packet instanceof InvalidateImageS2CPacket) return INVALIDATE_IMAGE;
        if (packet instanceof ServerConfigSyncS2CPacket) return CONFIG_SYNC;
        throw new IllegalArgumentException("Unregistered packet type: " + packet.getClass().getName());
    }

    private static void write(Object packet, FriendlyByteBuf buf) {
        if (packet instanceof UpdateScreenSelectedImageC2SPacket p) p.write(buf);
        else if (packet instanceof UpdateScreenS2CPacket p) p.write(buf);
        else if (packet instanceof UpdateScreenAspectRatioC2SPacket p) p.write(buf);
        else if (packet instanceof RequestImageDownloadC2SPacket p) p.write(buf);
        else if (packet instanceof RequestImageListC2SPacket p) p.write(buf);
        else if (packet instanceof UpdateImageListS2CPacket p) p.write(buf);
        else if (packet instanceof UploadImageChunkC2SPacket p) p.write(buf);
        else if (packet instanceof ImageDownloadChunkS2CPacket p) p.write(buf);
        else if (packet instanceof UpdateScreenIdC2SPacket p) p.write(buf);
        else if (packet instanceof DownloadImageFromUrlC2SPacket p) p.write(buf);
        else if (packet instanceof RemoveImageC2SPacket p) p.write(buf);
        else if (packet instanceof InvalidateImageS2CPacket p) p.write(buf);
        else if (packet instanceof ServerConfigSyncS2CPacket p) p.write(buf);
        else throw new IllegalArgumentException("Unregistered packet type: " + packet.getClass().getName());
    }
}
