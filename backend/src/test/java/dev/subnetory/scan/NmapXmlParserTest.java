package dev.subnetory.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du parseur XML Nmap.
 *
 * <p>Utilise le fichier fixture {@code test/resources/fixtures/nmap-scan-result.xml}
 * pour tester le parsing sans dépendance à un vrai Nmap.</p>
 */
class NmapXmlParserTest {

    @Test
    @DisplayName("Parse fixture XML — 4 hôtes UP retournés, 1 DOWN ignoré")
    void parse_fixture_returnsUpHostsOnly() throws Exception {
        List<NmapXmlParser.NmapHost> hosts = parseFixture();
        assertThat(hosts).hasSize(4);
    }

    @Test
    @DisplayName("Hôte avec IP + MAC + hostname PTR — tous les champs extraits")
    void parse_fullHost_extractsAllFields() throws Exception {
        NmapXmlParser.NmapHost host = findByIp(parseFixture(), "192.168.1.10");
        assertThat(host.ip()).isEqualTo("192.168.1.10");
        assertThat(host.mac()).isEqualTo("aa:bb:cc:dd:ee:01");
        // Le point final du FQDN doit être supprimé
        assertThat(host.hostname()).isEqualTo("srv-web-001.local");
    }

    @Test
    @DisplayName("Hôte sans MAC ni hostname — IP extraite, autres champs null")
    void parse_ipOnly_returnsNullMacAndHostname() throws Exception {
        NmapXmlParser.NmapHost host = findByIp(parseFixture(), "192.168.1.20");
        assertThat(host.ip()).isEqualTo("192.168.1.20");
        assertThat(host.mac()).isNull();
        assertThat(host.hostname()).isNull();
    }

    @Test
    @DisplayName("Hôte avec hostname PTR uniquement — MAC null")
    void parse_hostnameOnly_returnsMacNull() throws Exception {
        NmapXmlParser.NmapHost host = findByIp(parseFixture(), "192.168.1.30");
        assertThat(host.hostname()).isEqualTo("pc-compta-01.domain.local");
        assertThat(host.mac()).isNull();
    }

    @Test
    @DisplayName("Hôte DOWN — ne doit pas apparaître dans les résultats")
    void parse_downHost_isNotIncluded() throws Exception {
        List<NmapXmlParser.NmapHost> hosts = parseFixture();
        assertThat(hosts).noneMatch(h -> "192.168.1.50".equals(h.ip()));
    }

    @Test
    @DisplayName("MAC en majuscules — normalisée en minuscules")
    void parse_uppercaseMac_isNormalized() throws Exception {
        NmapXmlParser.NmapHost host = findByIp(parseFixture(), "192.168.1.40");
        assertThat(host.mac()).isEqualTo("ff:ee:dd:cc:bb:aa");
    }

    @Test
    @DisplayName("Hostname de type 'user' accepté comme fallback")
    void parse_userHostname_isAccepted() throws Exception {
        NmapXmlParser.NmapHost host = findByIp(parseFixture(), "192.168.1.40");
        assertThat(host.hostname()).isEqualTo("mac-pro-01");
    }

    @Test
    @DisplayName("Parse depuis String — même résultat que depuis InputStream")
    void parseString_producesIdenticalResults() throws Exception {
        String xml = new String(NmapXmlParserTest.class
                .getResourceAsStream("/fixtures/nmap-scan-result.xml")
                .readAllBytes());
        List<NmapXmlParser.NmapHost> hosts = NmapXmlParser.parseString(xml);
        assertThat(hosts).hasSize(4);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private List<NmapXmlParser.NmapHost> parseFixture() throws Exception {
        InputStream xml = NmapXmlParserTest.class
                .getResourceAsStream("/fixtures/nmap-scan-result.xml");
        assertThat(xml).as("Fixture file must be present").isNotNull();
        return NmapXmlParser.parse(xml);
    }

    private NmapXmlParser.NmapHost findByIp(List<NmapXmlParser.NmapHost> hosts, String ip) {
        return hosts.stream()
                .filter(h -> ip.equals(h.ip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Host not found: " + ip));
    }
}
