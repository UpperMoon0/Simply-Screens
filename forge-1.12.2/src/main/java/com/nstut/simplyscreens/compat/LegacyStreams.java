package com.nstut.simplyscreens.compat;
import java.io.IOException;
import java.io.InputStream;
public final class LegacyStreams {
 private LegacyStreams() { }
 public static byte[] readAll(InputStream input)throws IOException { byte[] bytes=Java8.readNBytes(input,com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE_LIMIT+1);if(bytes.length>com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE_LIMIT)throw new IOException("Image exceeds decode input budget");return bytes; }
}
