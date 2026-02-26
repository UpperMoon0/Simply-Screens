package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class UpdateScreenIdC2SPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenIdC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_id_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenIdC2SPacket> CODEC =
            StreamCodec.ofMember(UpdateScreenIdC2SPacket::write, UpdateScreenIdC2SPacket::new);

    private final BlockPos pos;
    private final String screenId;

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId) {
        this.pos = pos;
        this.screenId = screenId;
    }

    public UpdateScreenIdC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        screenId = buf.readUtf();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getPos() {
        return pos;
    }

    public String getScreenId() {
        return screenId;
    }

    public static void handle(UpdateScreenIdC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            var blockEntity = level.getBlockEntity(packet.pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    anchor.setScreenId(packet.screenId);

                    // Also update the registry with the current image ID
                    if (packet.screenId != null && !packet.screenId.isEmpty() && anchor.getImageId() != null) {
                        ScreenRegistry.setImageId(packet.screenId, anchor.getImageId());
                        ScreenRegistry.saveRegistry();
                    }
                }
            }
        });
    }
}
