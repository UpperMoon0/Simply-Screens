package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ClientImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

public class UpdateScreenWithCachedImageS2CPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "update_screen_with_cached_image");

    private final BlockPos blockPos;
    private final String imageHash;
    private final boolean maintainAspectRatio;

    public UpdateScreenWithCachedImageS2CPacket(BlockPos blockPos, String imageHash, boolean maintainAspectRatio) {
        this.blockPos = blockPos;
        this.imageHash = imageHash;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenWithCachedImageS2CPacket(FriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.imageHash = buf.readUtf();
        this.maintainAspectRatio = buf.readBoolean();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(imageHash);
        buf.writeBoolean(maintainAspectRatio);
    }

    public static void handle(UpdateScreenWithCachedImageS2CPacket msg, Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientImageCache.handleUpdateScreenWithCachedImage(msg));
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public String getImageHash() {
        return imageHash;
    }

    public boolean shouldMaintainAspectRatio() {
        return maintainAspectRatio;
    }
}