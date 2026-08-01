package dev.subnetory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Validation PostgreSQL 17 de la recherche SQL native des IP disponibles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
class AddressRepositoryAvailableIpIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
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

    @Autowired
    private AddressRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contextId;
    private Long siteId;

    @BeforeEach
    void prepareData() {
        jdbcTemplate.update("DELETE FROM addresses");
        jdbcTemplate.update("DELETE FROM subnets");
        jdbcTemplate.update("DELETE FROM sites");

        contextId = jdbcTemplate.queryForObject(
                "SELECT id FROM contexts WHERE name = 'Default'",
                Long.class);
        siteId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sites (name, code, context_id)
                VALUES ('Allocation SQL Test', 'ALLOC-SQL', ?)
                RETURNING id
                """,
                Long.class,
                contextId);
    }

    @Test
    @DisplayName("/30 : exclut network et broadcast")
    void classic30_excludesNetworkAndBroadcast() {
        Long subnetId = insertSubnet("192.0.2.0/30", null);

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly("192.0.2.1", "192.0.2.2");
    }

    @Test
    @DisplayName("/31 : retourne les deux adresses point-a-point")
    void pointToPoint31_returnsBothAddresses() {
        Long subnetId = insertSubnet("192.0.2.10/31", null);

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly("192.0.2.10", "192.0.2.11");
    }

    @Test
    @DisplayName("/31 : exclut la gateway reservee")
    void pointToPoint31_excludesGateway() {
        Long subnetId = insertSubnet("192.0.2.10/31", "192.0.2.10");

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly("192.0.2.11");
    }

    @Test
    @DisplayName("/32 : retourne l'adresse host si elle est libre")
    void host32_returnsSingleAddressWhenFree() {
        Long subnetId = insertSubnet("192.0.2.42/32", null);

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly("192.0.2.42");
    }

    @Test
    @DisplayName("/32 : retourne vide si l'adresse est assignee")
    void host32_returnsEmptyWhenAssigned() {
        Long subnetId = insertSubnet("192.0.2.42/32", null);
        insertAddress(subnetId, "192.0.2.42");

        assertThat(repository.findAvailableIps(subnetId, 5)).isEmpty();
    }

    @Test
    @DisplayName("Subnet classique : exclut gateway et IP assignees dans l'ordre")
    void classicSubnet_excludesGatewayAndAssignedAddresses() {
        Long subnetId = insertSubnet("198.51.100.0/29", "198.51.100.1");
        insertAddress(subnetId, "198.51.100.2");
        insertAddress(subnetId, "198.51.100.4");

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly(
                        "198.51.100.3",
                        "198.51.100.5",
                        "198.51.100.6");
    }

    @Test
    @DisplayName("Subnet plein : retourne une liste vide")
    void fullSubnet_returnsEmptyList() {
        Long subnetId = insertSubnet("203.0.113.0/30", null);
        insertAddress(subnetId, "203.0.113.1");
        insertAddress(subnetId, "203.0.113.2");

        assertThat(repository.findAvailableIps(subnetId, 5)).isEmpty();
    }

    @Test
    @DisplayName("Grand /8 : retourne seulement les premiers trous demandes")
    void largeNetwork_returnsFirstLimitedGaps() {
        Long subnetId = insertSubnet("10.0.0.0/8", "10.0.0.1");
        insertAddress(subnetId, "10.0.0.2");
        insertAddress(subnetId, "10.0.0.3");
        insertAddress(subnetId, "10.0.0.5");

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly(
                        "10.0.0.4",
                        "10.0.0.6",
                        "10.0.0.7",
                        "10.0.0.8",
                        "10.0.0.9");
    }

    @Test
    @DisplayName("IP utilisee dans un subnet chevauchant : reste disponible pour le subnet cible")
    void addressFromOverlappingSubnet_isExcluded() {
        Long targetSubnetId = insertSubnet("10.0.0.0/8", "10.0.0.1");
        Long overlappingSubnetId = insertSubnet("10.0.0.0/24", null);
        insertAddress(overlappingSubnetId, "10.0.0.2");

        assertThat(repository.findAvailableIps(targetSubnetId, 3))
                .containsExactly("10.0.0.2", "10.0.0.3", "10.0.0.4");
    }

    @Test
    @DisplayName("Gateway hors subnet : ne reserve aucune adresse candidate")
    void gatewayOutsideSubnet_isIgnored() {
        Long subnetId = insertSubnet("192.0.2.0/30", "198.51.100.1");

        assertThat(repository.findAvailableIps(subnetId, 5))
                .containsExactly("192.0.2.1", "192.0.2.2");
    }

    private Long insertSubnet(String network, String gateway) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO subnets (network, gateway, context_id, site_id, description)
                VALUES (CAST(? AS cidr), CAST(? AS inet), ?, ?, 'Allocation SQL test')
                RETURNING id
                """,
                Long.class,
                network,
                gateway,
                contextId,
                siteId);
    }

    private void insertAddress(Long subnetId, String address) {
        jdbcTemplate.update(
                """
                INSERT INTO addresses (
                    address, context_id, site_id, subnet_id,
                    modified_by, discovery_source
                )
                VALUES (CAST(? AS inet), ?, ?, ?, 'test', 'manual')
                """,
                address,
                contextId,
                siteId,
                subnetId);
    }
}
