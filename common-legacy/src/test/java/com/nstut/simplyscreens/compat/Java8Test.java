package com.nstut.simplyscreens.compat;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;
class Java8Test {
    @Test void boundedReadNeverConsumesBeyondLimit() throws Exception {
        InputStream input=new ByteArrayInputStream(new byte[]{1,2,3,4});
        assertArrayEquals(new byte[]{1,2},Java8.readNBytes(input,2));assertEquals(3,input.read());
    }
    @Test void boundedReadHandlesShortReadsAndEmptyInput() throws Exception {
        assertArrayEquals(new byte[]{1,2},Java8.readNBytes(new ByteArrayInputStream(new byte[]{1,2}),8));
        assertEquals(0,Java8.readNBytes(new ByteArrayInputStream(new byte[0]),4).length);
    }
    @Test void oversizedEncodedImagesFailRatherThanSilentlyTruncate() {
        assertThrows(java.io.IOException.class,()->Java8.readAllBounded(new ByteArrayInputStream(new byte[]{1,2,3}),2));
    }
    @Test void stripPreservesUnicodeWhitespaceSemantics() {
        assertEquals("screen",Java8.strip("\u2003screen\u2003"));
        assertEquals("\u00a0screen\u00a0",Java8.strip("\u00a0screen\u00a0"));
    }
    @Test void collectionCopiesAreDetachedAndImmutable() {
        java.util.List<String> original=new java.util.ArrayList<>();original.add("a");
        java.util.List<String> copy=Java8.listCopy(original);original.add("b");assertEquals(1,copy.size());
        assertThrows(UnsupportedOperationException.class,()->copy.add("c"));
    }
    @Test void httpAdapterRejectsPrivateAndCredentialDestinationsBeforeOpening() {
        assertThrows(IllegalArgumentException.class,()->PublicImageHttp.get("http://127.0.0.1/image.png"));
        assertThrows(IllegalArgumentException.class,()->PublicImageHttp.get("https://user:pass@example.com/image.png"));
    }
}
