package dev.subnetory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Subnet;
import dev.subnetory.domain.Vlan;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Recherche textuelle des listes (contextes, sites, VLAN, sous-réseaux) sur PostgreSQL 17 :
 * correspondance partielle insensible à la casse, jokers littéraux, colonnes natives
 * ({@code cidr}, {@code inet}, {@code smallint}) et respect des contextes autorisés.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
class ListSearchSpecificationsIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("subnetory_test")
            .withUsername("subnetory")
            .withPassword("subnetory");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private NetworkContextRepository contextRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private VlanRepository vlanRepository;
    @Autowired private SubnetRepository subnetRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long ctxA;
    private Long ctxB;
    private Long siteA;
    private Long siteB;

    @BeforeEach
    void prepareData() {
        jdbc.update("DELETE FROM addresses");
        jdbc.update("DELETE FROM subnets");
        jdbc.update("DELETE FROM vlans");
        jdbc.update("DELETE FROM sites");
        jdbc.update("DELETE FROM contexts WHERE name IN ('SQUARE-SIIUM', 'AUTRE-CLIENT')");

        ctxA = insertContext("SQUARE-SIIUM", "Contexte principal");
        ctxB = insertContext("AUTRE-CLIENT", "Autre client");
        siteA = insertSite("Challans (SIIUM)", "CHL", ctxA);
        siteB = insertSite("Datacenter DC44", "DC44", ctxB);
        Long vlanA = insertVlan("DEMO-NUTANIX", 110, siteA);
        insertVlan("INTERCO_ARISTA", 10, siteA);
        insertVlan("MANAGEMENT", 10, siteB);
        insertSubnet("10.1.10.0/24", "10.1.10.254", "Nutanix demo", ctxA, siteA, vlanA);
        insertSubnet("10.2.0.0/24", "10.2.0.254", "Infra 100% DC", ctxB, siteB, null);
    }

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("id"));

    @Test
    @DisplayName("Contextes : nom partiel insensible à la casse, limité aux contextes autorisés")
    void contexts_partialCaseInsensitiveAndAllowedOnly() {
        var spec = ContextSpecifications.withFilters("square", List.of(ctxA, ctxB));
        assertThat(contextRepository.findAll(spec, PAGE).map(NetworkContext::getName).getContent())
                .containsExactly("SQUARE-SIIUM");

        var restricted = ContextSpecifications.withFilters("client", List.of(ctxA));
        assertThat(contextRepository.findAll(restricted, PAGE)).isEmpty();
    }

    @Test
    @DisplayName("Aucun contexte autorisé : aucun résultat, jamais tout l'inventaire")
    void noAllowedContext_returnsNothing() {
        assertThat(siteRepository.findAll(SiteSpecifications.withFilters(null, null, List.of()), PAGE)).isEmpty();
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters(null, null, null, List.of()), PAGE)).isEmpty();
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters(null, null, null, null, List.of()), PAGE)).isEmpty();
        assertThat(contextRepository.findAll(ContextSpecifications.withFilters(null, List.of()), PAGE)).isEmpty();
    }

    @Test
    @DisplayName("Sites : nom ou code, partiel et insensible à la casse")
    void sites_nameOrCode() {
        var all = List.of(ctxA, ctxB);
        assertThat(siteRepository.findAll(SiteSpecifications.withFilters("chal", null, all), PAGE)
                .map(Site::getCode).getContent()).containsExactly("CHL");
        assertThat(siteRepository.findAll(SiteSpecifications.withFilters("dc4", null, all), PAGE)
                .map(Site::getCode).getContent()).containsExactly("DC44");
        assertThat(siteRepository.findAll(SiteSpecifications.withFilters("dc4", ctxA, all), PAGE)).isEmpty();
    }

    @Test
    @DisplayName("VLAN : numéro (colonne smallint) ou nom, avec filtre de site")
    void vlans_vidOrName() {
        var all = List.of(ctxA, ctxB);
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("11", null, null, all), PAGE)
                .map(Vlan::getName).getContent()).containsExactly("DEMO-NUTANIX");
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("interco", null, null, all), PAGE)
                .map(Vlan::getName).getContent()).containsExactly("INTERCO_ARISTA");
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("10", null, siteB, all), PAGE)
                .map(Vlan::getName).getContent()).containsExactly("MANAGEMENT");
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("10", ctxB, null, List.of(ctxA)), PAGE)).isEmpty();
    }

    @Test
    @DisplayName("Sous-réseaux : CIDR, description et passerelle (colonnes cidr/inet)")
    void subnets_cidrDescriptionGateway() {
        var all = List.of(ctxA, ctxB);
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("10.1.10", null, null, null, all), PAGE)
                .map(Subnet::getNetwork).getContent()).containsExactly("10.1.10.0/24");
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("0.254", null, null, null, all), PAGE)
                .getTotalElements()).isEqualTo(2);
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("NUTANIX demo", null, null, null, all), PAGE)
                .getTotalElements()).isEqualTo(1);
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("10.2.0", null, null, null, List.of(ctxA)), PAGE)).isEmpty();
    }

    @Test
    @DisplayName("Jokers SQL saisis par l'utilisateur : traités littéralement")
    void wildcards_areLiteral() {
        var all = List.of(ctxA, ctxB);
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("100%", null, null, null, all), PAGE)
                .getTotalElements()).isEqualTo(1);
        assertThat(subnetRepository.findAll(SubnetSpecifications.withFilters("%", null, null, null, all), PAGE)
                .getTotalElements()).isEqualTo(1);
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("INTERCO_A", null, null, all), PAGE)
                .getTotalElements()).isEqualTo(1);
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("INTERCO.A", null, null, all), PAGE)
                .getTotalElements()).isZero();
        assertThat(vlanRepository.findAll(VlanSpecifications.withFilters("_", null, null, all), PAGE)
                .getTotalElements()).isEqualTo(1);
    }

    private Long insertContext(String name, String description) {
        return jdbc.queryForObject(
                "INSERT INTO contexts (name, description) VALUES (?, ?) RETURNING id",
                Long.class, name, description);
    }

    private Long insertSite(String name, String code, Long contextId) {
        return jdbc.queryForObject(
                "INSERT INTO sites (name, code, context_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, code, contextId);
    }

    private Long insertVlan(String name, int vid, Long siteId) {
        return jdbc.queryForObject(
                "INSERT INTO vlans (name, vid, site_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, vid, siteId);
    }

    private Long insertSubnet(String network, String gateway, String description,
                              Long contextId, Long siteId, Long vlanId) {
        return jdbc.queryForObject(
                """
                INSERT INTO subnets (network, gateway, description, context_id, site_id, vlan_id)
                VALUES (CAST(? AS cidr), CAST(? AS inet), ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, network, gateway, description, contextId, siteId, vlanId);
    }
}
