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

    /**
     * Regression (audit du 03/08/2026, correctif MOYEN) : un prefixe CIDR
     * hors bornes (ex. /33) ou negatif faisait deraper le calcul du masque
     * dans {@code matchesCidrOrExact} et pouvait aboutir a un masque nul
     * (toute IP "correspond"). Refuse desormais de demarrer avec un tel
     * prefixe, plutot que de le decouvrir seulement au moment d'une requete.
     */
    @Test
    void trustedProxyEnabled_withOutOfRangePrefix_failsToStart() {
        var resolver = new ClientIpResolver(true, "172.16.0.0/33");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hors bornes");
    }

    @Test
    void trustedProxyEnabled_withNegativePrefix_failsToStart() {
        var resolver = new ClientIpResolver(true, "172.16.0.0/-1");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hors bornes");
    }

    @Test
    void trustedProxyEnabled_withNonNumericPrefix_failsToStart() {
        var resolver = new ClientIpResolver(true, "172.16.0.0/abc");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefixe numerique");
    }

    @Test
    void trustedProxyEnabled_withInvalidNetworkAddress_failsToStart() {
        var resolver = new ClientIpResolver(true, "999.999.999.999/24");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adresse reseau IPv4 valide");
    }

    @Test
    void trustedProxyEnabled_withInvalidExactIp_failsToStart() {
        var resolver = new ClientIpResolver(true, "not-an-ip");

        assertThatThrownBy(resolver::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adresse IPv4 valide");
    }

    @Test
    void trustedProxyEnabled_withBoundaryPrefixes_startsNormally() {
        var resolver = new ClientIpResolver(true, "10.0.0.0/0,172.16.0.0/32");

        assertThatCode(resolver::validateConfiguration).doesNotThrowAnyException();
    }
}
