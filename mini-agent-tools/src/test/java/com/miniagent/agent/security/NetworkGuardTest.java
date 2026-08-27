package com.miniagent.agent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkGuardTest {

    private static final NetworkGuard.HostResolver PUBLIC_RESOLVER =
            host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};

    @Test
    void allowsPublicWebAndWebSocketTargets() {
        NetworkGuard guard = guard(PUBLIC_RESOLVER);

        assertThat(guard.validateUrl("https://www.example.com/path?q=1")).isNull();
        assertThat(guard.validateWebSocketUrl("wss://stream.example.com/socket")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.1", "100.64.0.1", "127.0.0.2", "169.254.169.254",
            "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.168.1.1",
            "198.18.0.1", "198.51.100.2", "203.0.113.3", "224.0.0.1",
            "fc00::1", "fe80::1", "2001:db8::1", "2002:c000:0204::1"
    })
    void blocksNonPublicResolvedAddresses(String address) throws Exception {
        NetworkGuard guard = guard(host -> new InetAddress[]{InetAddress.getByName(address)});

        assertThat(guard.validateUrl("https://public.example.com/resource"))
                .contains("SSRF");
    }

    @Test
    void blocksWhenAnyDnsAnswerIsPrivate() throws Exception {
        NetworkGuard guard = guard(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.0.2")
        });

        assertThat(guard.validateUrl("https://mixed.example.com"))
                .contains("10.0.0.2");
    }

    @Test
    void rejectsCredentialsReservedNamesAndTrailingDotBypasses() {
        AtomicInteger resolutions = new AtomicInteger();
        NetworkGuard guard = guard(host -> {
            resolutions.incrementAndGet();
            return PUBLIC_RESOLVER.resolve(host);
        });

        assertThat(guard.validateUrl("https://user:secret@www.example.com"))
                .contains("credentials");
        assertThat(guard.validateUrl("http://LOCALHOST./"))
                .contains("SSRF");
        assertThat(guard.validateUrl("https://service.internal./"))
                .contains("SSRF");
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void rejectsProtocolsPortsAndIpv6Zones() {
        NetworkGuard guard = guard(PUBLIC_RESOLVER);

        assertThat(guard.validateUrl("file:///etc/passwd")).contains("http/https");
        assertThat(guard.validateUrl("https://www.example.com:8443/path"))
                .contains("port 8443");
        assertThat(guard.validateUrl("http://[fe80::1%25eth0]/"))
                .contains("zone identifiers");
    }

    @Test
    void resolvesNumericIpv4FormsBeforeAllowing() {
        NetworkGuard guard = new NetworkGuard(
                true, Set.of(80, 443), InetAddress::getAllByName);

        assertThat(guard.validateUrl("http://2130706433/"))
                .contains("SSRF");
    }

    @Test
    void keepsSyntaxChecksWhenPrivateBlockingIsExplicitlyDisabled() {
        NetworkGuard guard = new NetworkGuard(
                false, Set.of(80, 443), host -> {
                    throw new AssertionError("resolver should not be called");
                });

        assertThat(guard.validateUrl("http://127.0.0.1/")).isNull();
        assertThat(guard.validateUrl("http://127.0.0.1:8080/"))
                .contains("port 8080");
        assertThat(guard.validateUrl("http://user@127.0.0.1/"))
                .contains("credentials");
    }

    private static NetworkGuard guard(NetworkGuard.HostResolver resolver) {
        return new NetworkGuard(true, Set.of(80, 443), resolver);
    }
}
