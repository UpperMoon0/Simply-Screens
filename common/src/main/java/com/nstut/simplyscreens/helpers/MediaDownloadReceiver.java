package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.ChunkedTransfer;
import com.nstut.packetweave.api.TransferId;
import com.nstut.packetweave.api.TransferLimits;
import com.nstut.packetweave.api.TransferRegistry;
import com.nstut.simplyscreens.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Image metadata policy around PacketWeave's bounded receiver. Used by every client target. */
public final class MediaDownloadReceiver {
    private static final UUID SERVER = new UUID(0, 0);
    private final TransferRegistry transfers;
    private final long byteLimit;
    private final Map<UUID, Header> headers = new HashMap<>();

    public MediaDownloadReceiver() {
        this(new TransferLimits(ChunkedTransfer.CHUNK_BYTES, Config.MAX_UPLOAD_SIZE_LIMIT,
                32, 32, 128L * 1024 * 1024, Duration.ofSeconds(30)), System::nanoTime);
    }

    MediaDownloadReceiver(TransferLimits limits, LongSupplier clock) {
        byteLimit = limits.transferBytes();
        transfers = new TransferRegistry(limits, (id, operation) -> true, clock);
    }

    private TransferId id(UUID image) { return new TransferId("simply_screens_download", SERVER, image); }

    public synchronized Download accept(UUID image, int index, int count, byte[] bytes, String extension) {
        reapExpired();
        TransferId id = id(image);
        if ((index == 0 && !isImageExtension(extension)) || (index != 0 && extension != null)) {
            cancel(image);
            return null;
        }
        Header header = headers.get(image);
        if (header == null) {
            if (!transfers.beginBounded(id, count, byteLimit)) return null;
            header = new Header(count);
            headers.put(image, header);
        }
        if (header.count != count || !transfers.accept(id, index, bytes)) {
            cancel(image);
            return null;
        }
        if (index == 0) header.extension = extension;
        byte[] result = transfers.complete(id);
        if (result == null) return null;
        headers.remove(image);
        return new Download(header.extension, result);
    }

    /** Request timers cover a missing response; an active receiver uses its idle expiry instead. */
    public synchronized boolean requestIfIdle(UUID image, TransferRequestCoordinator<UUID> requests, Runnable starter) {
        return !transfers.contains(id(image)) && requests.tryStart(image, starter);
    }

    public synchronized boolean isPending(UUID image) { return transfers.contains(id(image)); }
    public synchronized void cancel(UUID image) { transfers.cancel(id(image)); headers.remove(image); }
    public synchronized void clear() { transfers.clear(); headers.clear(); }
    public synchronized List<UUID> reapExpired() {
        transfers.reapExpired();
        List<UUID> expired = new ArrayList<>();
        headers.keySet().removeIf(image -> {
            if (transfers.contains(id(image))) return false;
            expired.add(image);
            return true;
        });
        return expired;
    }

    private static boolean isImageExtension(String extension) {
        return "png".equals(extension) || "jpg".equals(extension) || "jpeg".equals(extension);
    }
    private static final class Header {
        final int count;
        String extension;
        Header(int count) { this.count = count; }
    }
    public record Download(String extension, byte[] bytes) { }
}
