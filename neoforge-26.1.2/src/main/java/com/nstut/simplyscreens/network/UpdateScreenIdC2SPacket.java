package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

public class UpdateScreenIdC2SPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenIdC2SPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_id_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenIdC2SPacket> CODEC =
            StreamCodec.ofMember(UpdateScreenIdC2SPacket::write, UpdateScreenIdC2SPacket::new);

    private final BlockPos pos;
    private final String screenId;
    private final UUID selectedImageId;

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId) {
        this(pos, screenId, null);
    }

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId, UUID selectedImageId) {
        this.pos = pos;
        this.screenId = screenId;
        this.selectedImageId = selectedImageId;
    }

    public UpdateScreenIdC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        String readScreenId = buf.readUtf(64);
        screenId = readScreenId.isEmpty() ? null : readScreenId;
        selectedImageId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId != null ? screenId : "", 64);
        buf.writeBoolean(selectedImageId != null);
        if (selectedImageId != null) {
            buf.writeUUID(selectedImageId);
        }
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

    public UUID getSelectedImageId() {
        return selectedImageId;
    }

    public static void handle(UpdateScreenIdC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) {
                return;
            }
            if (!ScreenPacketSecurity.canModify(player, packet.pos)) return;
            ServerLevel level = player.level();
            var blockEntity = level.getBlockEntity(packet.pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    boolean administrator = player.level().getServer().getPlayerList().isOp(player.nameAndId());
                    String normalizedId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(packet.screenId);
                    if (packet.screenId != null && !packet.screenId.isBlank() && normalizedId.isEmpty()) return;
                    if (packet.selectedImageId != null && !ServerImageManager.canPlayerAccessImage(
                            player.level().getServer(), packet.selectedImageId, player.getUUID().toString())) return;
                    if (!normalizedId.equals(anchor.getScreenId())) {
                        if (!normalizedId.isEmpty() && !ScreenRegistry.claimScreenId(normalizedId, player.getUUID(), administrator)) return;
                        anchor.setScreenId(normalizedId);
                    }
                    if (packet.selectedImageId != null) {
                        anchor.setImageId(packet.selectedImageId);
                    }
                }
            }
        });
    }
}
