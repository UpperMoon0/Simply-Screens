package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class UpdateScreenSelectedImageC2SPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenSelectedImageC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_selected_image_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenSelectedImageC2SPacket> CODEC = 
            StreamCodec.ofMember(UpdateScreenSelectedImageC2SPacket::write, UpdateScreenSelectedImageC2SPacket::new);

    private final BlockPos pos;
    private final UUID imageId;

    public UpdateScreenSelectedImageC2SPacket(BlockPos pos, UUID imageId) {
        this.pos = pos;
        this.imageId = imageId;
    }

    public UpdateScreenSelectedImageC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        imageId = buf.readUUID();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUUID(imageId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getPos() {
        return pos;
    }

    public UUID getImageId() {
        return imageId;
    }

    public static void handle(UpdateScreenSelectedImageC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) {
                return;
            }
            if (!ScreenPacketSecurity.canModify(player, packet.pos) || !ServerImageManager.canPlayerAccessImage(player.getServer(), packet.imageId, player.getUUID().toString())) return;
            ServerLevel level = player.serverLevel();
            var blockEntity = level.getBlockEntity(packet.pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    anchor.setImageId(packet.imageId);
                }
            }
        });
    }
}
