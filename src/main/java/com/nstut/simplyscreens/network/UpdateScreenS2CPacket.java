package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@Getter
public class UpdateScreenS2CPacket {
    // Getters for the packet data
    private final BlockPos pos;
    private final String imagePath;

    // Constructor to initialize the packet with data
    public UpdateScreenS2CPacket(BlockPos pos, String imagePath) {
        this.pos = pos;
        this.imagePath = imagePath;
    }

    // Decoder to read the data from the buffer on the client side
    public UpdateScreenS2CPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.imagePath = buffer.readUtf(32767);  // Max length of the string
    }

    // Method to encode the data into a buffer to be sent to the client
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(imagePath);
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
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
