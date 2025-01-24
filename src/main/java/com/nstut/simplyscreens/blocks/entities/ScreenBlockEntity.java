package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenS2CPacket;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.logging.Logger;

@Getter
public class ScreenBlockEntity extends BlockEntity {

    private static final Logger LOGGER = Logger.getLogger(ScreenBlockEntity.class.getName());

    private String imagePath = "";

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
    }

    public void setImagePath(String path) {
        this.imagePath = path;
        sendPacketToClients();
        setChanged();
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        if (!imagePath.isEmpty()) {  // Check if imagePath is not empty
            tag.putString("imagePath", imagePath);
            sendPacketToClients();
        }
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        if (tag.contains("imagePath")) {
            this.imagePath = tag.getString("imagePath");
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        CompoundTag tag = new CompoundTag();
        tag.putString("imagePath", imagePath);
        return ClientboundBlockEntityDataPacket.create(this, (blockEntity) -> tag);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        if (tag.contains("imagePath")) {
            this.imagePath = tag.getString("imagePath");
        }
    }

    private void sendPacketToClients() {
        if (level != null && !level.isClientSide) {
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, imagePath);
            PacketRegistries.sendToClients(packet);
        }
    }

    public void tick(Level level) {
    }
}
