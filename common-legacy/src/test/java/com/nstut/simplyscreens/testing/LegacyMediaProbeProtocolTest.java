package com.nstut.simplyscreens.testing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMediaProbeProtocolTest {
    @Test void worldOrPlayerDisappearanceReplacementAndDeadlineRejectActiveSession() {
        Object world = new Object(); UUID player = UUID.randomUUID();
        assertDoesNotThrow(() -> LegacyMediaProbeProtocol.requireSession(world, player, 1, world, player));
        assertThrows(IllegalStateException.class, () -> LegacyMediaProbeProtocol.requireSession(world, player, 1, null, player));
        assertThrows(IllegalStateException.class, () -> LegacyMediaProbeProtocol.requireSession(world, player, 1, new Object(), player));
        assertThrows(IllegalStateException.class, () -> LegacyMediaProbeProtocol.requireSession(world, player, 1, world, null));
        assertThrows(IllegalStateException.class, () -> LegacyMediaProbeProtocol.requireSession(world, player, 1, world, UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> LegacyMediaProbeProtocol.requireSession(world, player, LegacyMediaProbeProtocol.TIMEOUT_NANOS + 1, world, player));
    }
    @Test void ownedPngIsDeterministicSmallAndHasExactPixels() throws Exception {
        byte[] png = LegacyMediaProbeProtocol.png();
        assertArrayEquals(png, LegacyMediaProbeProtocol.png());
        assertTrue(png.length > 16 && png.length < 8192);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(16, image.getWidth()); assertEquals(16, image.getHeight());
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            assertEquals(LegacyMediaProbeProtocol.argb(x, y), image.getRGB(x, y));
        assertEquals(0xffeda412, LegacyMediaProbeProtocol.abgr(0, 0));
    }
    @Test void noGateOrPartialCombinationCanPass() {
        UUID id = UUID.randomUUID();
        LegacyMediaProbeProtocol.Evidence evidence = new LegacyMediaProbeProtocol.Evidence();
        assertFalse(evidence.passed());
        evidence.server(id); assertFalse(evidence.passed());
        evidence.download(id, "png", LegacyMediaProbeProtocol.png()); assertFalse(evidence.passed());
        evidence.texture(id, 16, 16, true, true); assertFalse(evidence.passed());
        evidence.geometry(id); assertTrue(evidence.passed());
    }
    @Test void downloadAndRenderMayPrecedeServerAcknowledgmentButCannotPassWithoutIt() {
        UUID id = UUID.randomUUID();
        LegacyMediaProbeProtocol.Evidence evidence = new LegacyMediaProbeProtocol.Evidence();
        evidence.download(id, "png", LegacyMediaProbeProtocol.png());
        evidence.texture(id, 16, 16, true, true); evidence.geometry(id);
        assertFalse(evidence.passed()); evidence.server(id); assertTrue(evidence.passed());
    }
    @Test void wrongContentOrIdentityFailsPermanently() {
        UUID id = UUID.randomUUID();
        LegacyMediaProbeProtocol.Evidence bytes = new LegacyMediaProbeProtocol.Evidence();
        assertThrows(IllegalStateException.class, () -> bytes.download(id, "png", new byte[16]));
        assertThrows(IllegalStateException.class, () -> bytes.download(id, "png", LegacyMediaProbeProtocol.png()));
        assertFalse(bytes.passed());
        LegacyMediaProbeProtocol.Evidence identity = new LegacyMediaProbeProtocol.Evidence();
        identity.server(id);
        assertThrows(IllegalStateException.class, () -> identity.download(UUID.randomUUID(), "png", LegacyMediaProbeProtocol.png()));
        assertFalse(identity.passed());
    }
    @Test void placeholderTexturesAndSyntheticGeometryCannotPass() {
        UUID id = UUID.randomUUID();
        LegacyMediaProbeProtocol.Evidence early = new LegacyMediaProbeProtocol.Evidence();
        assertThrows(IllegalStateException.class, () -> early.geometry(id));
        for (int condition = 0; condition < 4; condition++) {
            final int caseId = condition;
            LegacyMediaProbeProtocol.Evidence evidence = new LegacyMediaProbeProtocol.Evidence();
            evidence.download(id, "png", LegacyMediaProbeProtocol.png());
            assertThrows(IllegalStateException.class, () -> evidence.texture(id,
                    caseId == 0 ? 256 : 16, caseId == 1 ? 256 : 16, caseId != 2, caseId != 3));
            assertFalse(evidence.passed());
        }
    }
    @Test void explicitFailureCannotBeRecoveredIntoPass() {
        UUID id = UUID.randomUUID();
        LegacyMediaProbeProtocol.Evidence evidence = new LegacyMediaProbeProtocol.Evidence();
        evidence.server(id); evidence.download(id, "png", LegacyMediaProbeProtocol.png());
        evidence.texture(id, 16, 16, true, true); evidence.geometry(id); assertTrue(evidence.passed());
        assertThrows(IllegalStateException.class, () -> evidence.fail("deadline"));
        assertFalse(evidence.passed());
    }
}
