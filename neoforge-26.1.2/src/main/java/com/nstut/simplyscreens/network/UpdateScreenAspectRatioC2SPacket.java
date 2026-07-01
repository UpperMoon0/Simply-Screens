package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class UpdateScreenAspectRatioC2SPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenAspectRatioC2SPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_aspect_ratio_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenAspectRatioC2SPacket> CODEC = 
            StreamCodec.ofMember(UpdateScreenAspectRatioC2SPacket::write, UpdateScreenAspectRatioC2SPacket::new);

    private final BlockPos pos;
    private final boolean maintainAspectRatio;

    public UpdateScreenAspectRatioC2SPacket(BlockPos pos, boolean maintainAspectRatio) {
        this.pos = pos;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenAspectRatioC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        maintainAspectRatio = buf.readBoolean();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(maintainAspectRatio);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getPos() {
        return pos;
    }

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public static void handle(UpdateScreenAspectRatioC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) {
                return;
            }
            ServerLevel level = player.level();
            var blockEntity = level.getBlockEntity(packet.pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    anchor.setMaintainAspectRatio(packet.maintainAspectRatio);
                }
            }
        });
    }
}


