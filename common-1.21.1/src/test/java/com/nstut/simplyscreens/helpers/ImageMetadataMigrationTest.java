package com.nstut.simplyscreens.helpers;

import com.google.gson.Gson;
import com.nstut.simplyscreens.ImageNameSanitizer;
import com.nstut.simplyscreens.network.UpdateImageListS2CPacket;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.*;

class ImageMetadataMigrationTest {

    private static final Gson GSON = new Gson();

    @Test
    void legacyOversizedName_isSanitizedOnLoad() {
        String longName = "A".repeat(300);
        String json = "{\"name\":\"" + longName + "\",\"id\":\"" + UUID.randomUUID() +
                "\",\"extension\":\"png\",\"ownerUUID\":null}";

        ImageMetadata raw = GSON.fromJson(json, ImageMetadata.class);
        assertTrue(raw.getName().length() > 128, "Gson must reproduce the unsanitized legacy name");

        ImageMetadata normalized = ImageMetadata.validateAndNormalize(raw, null);
        assertNotNull(normalized);
        assertTrue(normalized.getName().length() <= ImageNameSanitizer.MAX_LENGTH);
    }

    @Test
    void invalidExtension_orMissingFile_isRejected() throws Exception {
        String id = UUID.randomUUID().toString();
        String json = "{\"name\":\"ok\",\"id\":\"" + id + "\",\"extension\":\"exe\",\"ownerUUID\":null}";
        ImageMetadata raw = GSON.fromJson(json, ImageMetadata.class);
        assertNull(ImageMetadata.validateAndNormalize(raw, null), "non-allowlisted extension must be rejected");

        Path dir = Files.createTempDirectory("ss-meta");
        try {
            json = "{\"name\":\"ok\",\"id\":\"" + id + "\",\"extension\":\"png\",\"ownerUUID\":null}";
            raw = GSON.fromJson(json, ImageMetadata.class);
            assertNull(ImageMetadata.validateAndNormalize(raw, dir),
                    "metadata without a backing image file must be rejected");
            Files.createFile(dir.resolve(id + ".png"));
            assertNotNull(ImageMetadata.validateAndNormalize(raw, dir),
                    "metadata with a matching image file must be accepted");
        } finally {
            Files.walk(dir).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }

    @Test
    void normalizedMetadata_encodesIntoGalleryPacketWithoutError() {
        String longName = "B".repeat(200);
        String json = "{\"name\":\"" + longName + "\",\"id\":\"" + UUID.randomUUID() +
                "\",\"extension\":\"PNG\",\"ownerUUID\":null}";
        ImageMetadata raw = GSON.fromJson(json, ImageMetadata.class);
        ImageMetadata normalized = ImageMetadata.validateAndNormalize(raw, null);
        assertNotNull(normalized);

        UpdateImageListS2CPacket packet = new UpdateImageListS2CPacket(List.of(normalized));
        ByteBuf byteBuf = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(byteBuf, Mockito.mock(RegistryAccess.class));
        assertDoesNotThrow(() -> packet.write(buf));
    }
}
