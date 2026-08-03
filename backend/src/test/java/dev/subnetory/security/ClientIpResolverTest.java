package dev.subnetory.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression (audit du 03/08/2026, correctif ELEVEE) : activer
 * {@code subnetory.security.trusted-proxy=true} sans renseigner
 * {@code subnetory.security.trusted-proxy-cidrs} etait accepte silencieusement
 * et faisait confiance a X-Forwarded-For/X-Real-IP en provenance de
 * n'importe quelle connexion directe (voir {@code ClientIpResolver#isFromTrustedProxy}).
 * L'application doit desormais refuser de demarrer dans cette configuration.
 */
class ClientIpResolverTest {

    @Test
    void trustedProxyEnabled_withoutCidrs_failsToStart() {
        var resolver = new ClientIpResolver(true, "");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxy-cidrs");
    }

    @Test
    void trustedProxyEnabled_withBlankCidrs_failsToStart() {
        var resolver = new ClientIpResolver(true, "   ");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trustedProxyEnabled_withCidrs_startsNormally() {
        var resolver = new ClientIpResolver(true, "172.16.0.0/12");

        assertThatCode(resolver::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void trustedProxyDisabled_withoutCidrs_startsNormally() {
        var resolver = new ClientIpResolver(false, "");

        assertThatCode(resolver::validateConfiguration).doesNotThrowAnyException();
    }
}
