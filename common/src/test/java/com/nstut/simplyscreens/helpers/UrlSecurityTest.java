package com.nstut.simplyscreens.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlSecurityTest {
    @Test void rejectsLoopbackPrivateLinkLocalAndIpv6LocalAddresses() {
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://127.0.0.1/test"));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://10.1.2.3/test"));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://169.254.169.254/latest/meta-data"));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://[::1]/test"));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://[fc00::1]/test"));
    }

    @Test void rejectsCredentialsAndNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurity.requirePublicHttpUrl("http://user:pass@example.com/image.png"));
    }

    @Test void acceptsPublicNumericAddressWithoutDependingOnDns() {
        assertDoesNotThrow(() -> UrlSecurity.requirePublicHttpUrl("https://1.1.1.1/image.png"));
    }
}
