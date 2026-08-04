package dev.subnetory.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
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

    // -------------------------------------------------------
    // resolve() — mode direct (trusted-proxy=false, comportement par defaut)
    //
    // Couverture ajoutee (audit 04/08/2026, ~57% de couverture initiale sur
    // cette classe) : jusqu'ici seule validateConfiguration() etait testee,
    // pas la logique de resolution elle-meme (le coeur du modele de
    // confiance decrit dans la javadoc de la classe).
    // -------------------------------------------------------

    @Test
    void resolve_directMode_ignoresForwardedHeadersEvenIfPresent() {
        var resolver = new ClientIpResolver(false, "");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        request.addHeader("X-Real-IP", "7.7.7.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolve_directMode_blankRemoteAddr_returnsUnknown() {
        var resolver = new ClientIpResolver(false, "");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("");

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }

    // -------------------------------------------------------
    // resolve() — mode proxy (trusted-proxy=true)
    // -------------------------------------------------------

    @Test
    void resolve_proxyMode_untrustedDirectConnection_ignoresHeaders() {
        var resolver = new ClientIpResolver(true, "10.0.0.0/8");
        var request = new MockHttpServletRequest();
        // Connexion directe hors de la plage de confiance : les en-tetes
        // presentes ne doivent pas etre utilises (protection contre la
        // falsification d'IP par un client qui atteint directement l'appli).
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolve_proxyMode_trustedExactIp_usesForwardedFor() {
        var resolver = new ClientIpResolver(true, "10.0.0.5");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.5");

        // Premiere IP de la chaine = client d'origine.
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void resolve_proxyMode_trustedCidrRange_usesForwardedFor_trimmed() {
        var resolver = new ClientIpResolver(true, "172.16.0.0/12");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("172.20.3.4");
        request.addHeader("X-Forwarded-For", "  198.51.100.42 ,172.20.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void resolve_proxyMode_trustedProxy_noForwardedFor_fallsBackToRealIp() {
        var resolver = new ClientIpResolver(true, "10.0.0.0/8");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Real-IP", "198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void resolve_proxyMode_trustedProxy_noHeadersAtAll_fallsBackToRemoteAddr() {
        var resolver = new ClientIpResolver(true, "10.0.0.0/8");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("10.1.2.3");
    }

    @Test
    void resolve_proxyMode_forwardedForBlankFirstEntry_returnsUnknown() {
        var resolver = new ClientIpResolver(true, "10.0.0.0/8");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", " ,198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }

    @Test
    void resolve_proxyMode_ipv6RemoteAddr_notMatchedByIpv4Cidr_fallsBackToRemoteAddr() {
        // ipv4ToLong() renvoie -1 pour une IPv6 : matchesCidrOrExact() est
        // fail-safe (non gere ici => non de confiance), donc les en-tetes
        // sont ignores meme si presents.
        var resolver = new ClientIpResolver(true, "10.0.0.0/8");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::1");
        request.addHeader("X-Forwarded-For", "198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
    }

    @Test
    void resolve_proxyMode_emptyCidrList_trustsAnyDirectConnection() {
        // Documente le comportement fail-open de isFromTrustedProxy() quand
        // trusted-proxy-cidrs est vide : en pratique inatteignable en
        // production car validateConfiguration() refuse ce demarrage, mais
        // le comportement de resolve() lui-meme reste a couvrir.
        var resolver = new ClientIpResolver(true, "");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }
}
