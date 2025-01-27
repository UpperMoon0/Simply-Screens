package com.nstut.simply_screens.network;

import com.nstut.simply_screens.blocks.entities.ScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UpdateScreenS2CPacket {
    private final BlockPos pos;
    private final String imagePath;
    private final BlockPos anchorPos;
    private final int screenWidth;
    private final int screenHeight;

    // Constructor to initialize the packet with data
    public UpdateScreenS2CPacket(BlockPos pos, String imagePath, BlockPos anchorPos, int screenWidth, int screenHeight) {
        this.pos = pos;
        this.imagePath = imagePath;
        this.anchorPos = anchorPos;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    // Decoder to read the data from the buffer on the client side
    public UpdateScreenS2CPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.imagePath = buffer.readUtf(32767);
        this.anchorPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
        this.screenWidth = buffer.readInt();
        this.screenHeight = buffer.readInt();
    }

    // Method to encode the data into a buffer to be sent to the client
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(imagePath);
        if (anchorPos != null) {
            buffer.writeBoolean(true);
            buffer.writeBlockPos(anchorPos);
        } else {
            buffer.writeBoolean(false);
        }
        buffer.writeInt(screenWidth);
        buffer.writeInt(screenHeight);
    }

    // Handler for processing the packet on the client side
    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            // Get the level and block entity on the client side
            Minecraft minecraft = Minecraft.getInstance();
            Level level = minecraft.level;
            if (level != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof ScreenBlockEntity) {
                    // Update the block entity's image path
                    ((ScreenBlockEntity) blockEntity).setImagePath(imagePath);
                    ((ScreenBlockEntity) blockEntity).setAnchorPos(anchorPos);
                    ((ScreenBlockEntity) blockEntity).setScreenWidth(screenWidth);
                    ((ScreenBlockEntity) blockEntity).setScreenHeight(screenHeight);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
