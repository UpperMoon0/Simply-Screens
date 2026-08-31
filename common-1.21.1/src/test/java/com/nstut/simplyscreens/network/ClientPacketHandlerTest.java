package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientPacketHandlerTest {

    @Test
    void vanillaBlockEntityUpdateRefreshesMatchingOpenScreen() {
        BlockPos pos = new BlockPos(10, 20, 30);
        BlockPos anchorPos = new BlockPos(9, 20, 30);
        UUID imageId = UUID.randomUUID();
        ScreenBlockEntity screen = mock(ScreenBlockEntity.class);
        ImageLoadScreen openScreen = mock(ImageLoadScreen.class);
        when(screen.getBlockPos()).thenReturn(pos);
        when(screen.getAnchorPos()).thenReturn(anchorPos);
        when(screen.getResolvedImageId()).thenReturn(imageId);
        when(screen.isMaintainAspectRatio()).thenReturn(false);
        when(screen.getScreenId()).thenReturn("linked-screen");
        when(openScreen.matchesScreenUpdate(pos, anchorPos)).thenReturn(true);

        ClientPacketHandler.notifyOpenScreen(openScreen, screen);

        verify(openScreen).updateScreenState(imageId, false, "linked-screen");
    }

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
