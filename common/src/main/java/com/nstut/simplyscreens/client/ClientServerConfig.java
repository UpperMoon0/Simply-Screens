package com.nstut.simplyscreens.client;

import com.nstut.simplyscreens.Config;

/** Client-only view of the current server's advertised upload policy. */
public final class ClientServerConfig {
    private ClientServerConfig() { }

    public static boolean disableUpload() { return disableUpload; }
    public static boolean disableUrlDownload() { return disableUrlDownload; }
    public static int maxUploadSize() { return maxUploadSize; }

    private static boolean disableUpload = Config.DISABLE_UPLOAD;
    private static boolean disableUrlDownload = Config.DISABLE_URL_DOWNLOAD;
    private static int maxUploadSize = Config.MAX_UPLOAD_SIZE;

    public static void apply(boolean uploadDisabled, boolean urlDisabled, int uploadLimit) {
        disableUpload = uploadDisabled;
        disableUrlDownload = urlDisabled;
        maxUploadSize = Math.max(Config.MIN_UPLOAD_SIZE, Math.min(Config.MAX_UPLOAD_SIZE_LIMIT, uploadLimit));
    }

    public static void reset() {
        disableUpload = Config.DISABLE_UPLOAD;
        disableUrlDownload = Config.DISABLE_URL_DOWNLOAD;
        maxUploadSize = Config.MAX_UPLOAD_SIZE;
    }
}
