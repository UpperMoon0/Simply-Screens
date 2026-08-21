package com.nstut.simplyscreens.client;

import com.nstut.simplyscreens.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientServerConfigTest {
    @AfterEach
    void reset() {
        ClientServerConfig.reset();
    }

    @Test
    void remoteValuesDoNotMutateCommonConfigAndAreClamped() {
        boolean localUploadDisabled = Config.DISABLE_UPLOAD;
        int localLimit = Config.MAX_UPLOAD_SIZE;

        ClientServerConfig.apply(!localUploadDisabled, true, Integer.MAX_VALUE);

        assertEquals(localUploadDisabled, Config.DISABLE_UPLOAD);
        assertEquals(localLimit, Config.MAX_UPLOAD_SIZE);
        assertEquals(Config.MAX_UPLOAD_SIZE_LIMIT, ClientServerConfig.maxUploadSize());
        assertEquals(!localUploadDisabled, ClientServerConfig.disableUpload());
    }

    @Test
    void disconnectResetRestoresLocalValues() {
        ClientServerConfig.apply(true, true, Config.MIN_UPLOAD_SIZE);
        ClientServerConfig.reset();

        assertEquals(Config.DISABLE_UPLOAD, ClientServerConfig.disableUpload());
        assertEquals(Config.DISABLE_URL_DOWNLOAD, ClientServerConfig.disableUrlDownload());
        assertEquals(Config.MAX_UPLOAD_SIZE, ClientServerConfig.maxUploadSize());
    }
}
