package com.nstut.simplyscreens.helpers;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Server-side URL policy. Every redirect target must be checked again. */
public final class UrlSecurity {
    private UrlSecurity() {}

    public static URI requirePublicHttpUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Only public HTTP(S) URLs without credentials are allowed");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("Host has no addresses");
            for (InetAddress address : addresses) {
                if (isBlocked(address.getAddress())) {
                    throw new IllegalArgumentException("Local and private network destinations are not allowed");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host cannot be resolved", e);
        }
        return uri;
    }

    static boolean isBlocked(byte[] a) {
        if (a.length == 4) {
            int x = a[0] & 255, y = a[1] & 255;
            return x == 0 || x == 10 || x == 127 || x >= 224 ||
                    (x == 100 && y >= 64 && y <= 127) || (x == 169 && y == 254) ||
                    (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 0) ||
                    (x == 192 && y == 168) || (x == 198 && (y == 18 || y == 19));
        }
        if (a.length == 16) {
            boolean allZero = true;
            for (byte b : a) allZero &= b == 0;
            if (allZero) return true;
            boolean loopback = true;
            for (int i = 0; i < 15; i++) loopback &= a[i] == 0;
            if (loopback && a[15] == 1) return true;
            int first = a[0] & 255, second = a[1] & 255;
            if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80) || first == 0xff) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= a[i] == 0;
            mapped &= a[10] == (byte) 0xff && a[11] == (byte) 0xff;
            if (mapped) return isBlocked(new byte[]{a[12], a[13], a[14], a[15]});
        }
        return false;
    }
}
