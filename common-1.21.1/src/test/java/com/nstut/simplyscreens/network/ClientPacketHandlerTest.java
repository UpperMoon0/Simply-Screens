package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ClientPacketHandlerTest {
    @Test
    void expandedChildIsAssignedToOriginalAnchorBeforeRenderState() {
        ScreenBlockEntity child = mock(ScreenBlockEntity.class);
        BlockPos anchor = new BlockPos(10, 20, 30);
        UUID imageId = UUID.randomUUID();

        ClientPacketHandler.applyScreenUpdate(child, anchor, imageId, true, "expanded", 5, 4);

        InOrder updates = inOrder(child);
        updates.verify(child).setAnchorPos(anchor);
        updates.verify(child).setImageId(imageId);
        updates.verify(child).setMaintainAspectRatio(true);
        updates.verify(child).setScreenWidth(5);
        updates.verify(child).setScreenHeight(4);
        updates.verify(child).setScreenId("expanded");
    }
}
