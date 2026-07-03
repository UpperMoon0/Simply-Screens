package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

class PacketRegistriesTest {
    @Test
    void commonRegistrationProvidesEveryServerToClientCodec() {
        try (MockedStatic<NetworkManager> network = mockStatic(NetworkManager.class)) {
            PacketRegistries.registerS2CPayloadTypes();

            network.verify(() -> NetworkManager.registerS2CPayloadType(
                    UpdateScreenS2CPacket.TYPE, UpdateScreenS2CPacket.CODEC));
            network.verify(() -> NetworkManager.registerS2CPayloadType(
                    UpdateImageListS2CPacket.TYPE, UpdateImageListS2CPacket.CODEC));
            network.verify(() -> NetworkManager.registerS2CPayloadType(
                    ImageDownloadChunkS2CPacket.TYPE, ImageDownloadChunkS2CPacket.CODEC));
        }
    }
}
