package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.UUID;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

public class UpdateScreenIdC2SPacket {
    public final BlockPos pos;
    public final String screenId;
    public final UUID selectedImageId;

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId) {
        this(pos, screenId, null);
    }

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId, UUID selectedImageId) {
        this.pos = pos;
        this.screenId = screenId;
        this.selectedImageId = selectedImageId;
    }

    public UpdateScreenIdC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        String readScreenId = buf.readUtf();
        screenId = readScreenId.isEmpty() ? null : readScreenId;
        selectedImageId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId != null ? screenId : "");
        buf.writeBoolean(selectedImageId != null);
        if (selectedImageId != null) {
            buf.writeUUID(selectedImageId);
        }
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            if (player == null) {
                return;
            }
            if (!ScreenPacketSecurity.canModify(player, pos)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    String normalizedId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(screenId);
                    if (screenId != null && !screenId.isBlank() && normalizedId.isEmpty()) return;
                    if (!normalizedId.equals(anchor.getScreenId())) {
                        if (!normalizedId.isEmpty() && !ScreenRegistry.claimScreenId(normalizedId, player.getUUID(), player.hasPermissions(2))) return;
                        anchor.setScreenId(normalizedId);
                    }
                    if (selectedImageId != null) {
                        if (!ServerImageManager.canPlayerAccessImage(player.serverLevel().getServer(), selectedImageId, player.getUUID().toString())) return;
                        anchor.setImageId(selectedImageId);
                    }
                }
            }
        });
    }
}
