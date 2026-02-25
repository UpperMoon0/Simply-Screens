package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.function.Supplier;

public class UpdateScreenWithCachedImageS2CPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "update_screen_with_cached_image");

    private final BlockPos blockPos;
    private final UUID imageId;
    private final boolean maintainAspectRatio;

    public UpdateScreenWithCachedImageS2CPacket(BlockPos blockPos, UUID imageId, boolean maintainAspectRatio) {
        this.blockPos = blockPos;
        this.imageId = imageId;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUUID(imageId);
        buf.writeBoolean(maintainAspectRatio);
    }

    public static UpdateScreenWithCachedImageS2CPacket read(FriendlyByteBuf buf) {
        return new UpdateScreenWithCachedImageS2CPacket(
                buf.readBlockPos(),
                buf.readUUID(),
                buf.readBoolean()
        );
    }

    public static void apply(UpdateScreenWithCachedImageS2CPacket msg, Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            if (context.get().getPlayer().level().getBlockEntity(msg.blockPos) instanceof ScreenBlockEntity screen) {
                screen.setImageId(msg.imageId);
                screen.setMaintainAspectRatio(msg.maintainAspectRatio);
            }
        });
    }
}