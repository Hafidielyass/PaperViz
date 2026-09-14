package dev.paperviz.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @ParameterizedTest(name = "reject {0}")
    @CsvSource({
            "0.0.0.0",
            "127.0.0.1",
            "127.8.8.8",
            "10.0.0.5",
            "10.255.255.255",
            "100.64.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254",
            "224.0.0.1",
            "255.255.255.255",
    })
    void rejectsPrivateAndReservedIpv4(String address) {
        assertThatThrownBy(() -> guard.check(address))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("internal");
    }

    @ParameterizedTest(name = "reject {0}")
    @CsvSource({
            "::1",
            "::",
            "fe80::1",
            "fc00::1",
    })
    void rejectsUnsafeIpv6(String address) {
        assertThatThrownBy(() -> guard.check(address))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("internal");
    }

    @Test
    void allowsPublicRoutableIpv4WithoutDns() {
        assertThatCode(() -> guard.check("8.8.8.8")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("172.32.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> guard.check(""))
                .isInstanceOf(IngestionException.class);
    }

    @Test
    void rejectsLoopbackHostnameThroughResolution() {
        // localhost comes back from the resolver as 127.0.0.1, which is exactly
        // the kind of target a crafted link should never be allowed to hit.
        assertThatThrownBy(() -> guard.check("localhost"))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("internal");
    }
}