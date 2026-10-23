package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.exception.SeriesConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Validation PostgreSQL 17 de la reservation en serie : detection des conflits
 * reelle (type {@code inet}), creation atomique et nommage incremente.
 *
 * <p>Le controle d'acces au contexte est simule : il est teste par ailleurs et
 * n'est pas l'objet de ces scenarios.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
class SeriesReservationServiceIT {

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

    @MockitoBean ContextAccessService contextAccessService;

    @Autowired SeriesReservationService service;
    @Autowired JdbcTemplate jdbcTemplate;

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
                VALUES ('Series Test', 'SERIES-IT', ?)
                RETURNING id
                """,
                Long.class,
                contextId);
    }

    @Test
    @DisplayName("create : la serie est ecrite en base avec noms incrementes")
    void create_persistsSeries() {
        Long subnetId = insertSubnet("10.10.10.0/24", "10.10.10.1");

        SeriesReservationResponse response = service.create(
                request(subnetId, "10.10.10.21", 3, null), "nahim");

        assertThat(response.canCreate()).isTrue();
        assertThat(storedAddresses(subnetId))
                .extracting(row -> row.get("ip"), row -> row.get("hostname"),
                        row -> row.get("modified_by"), row -> row.get("discovery_source"))
                .containsExactly(
                        tuple("10.10.10.21", "NTNX-HOST-01", "nahim", "manual"),
                        tuple("10.10.10.22", "NTNX-HOST-02", "nahim", "manual"),
                        tuple("10.10.10.23", "NTNX-HOST-03", "nahim", "manual"));
    }

    @Test
    @DisplayName("create : mode block + adresse deja reservee -> rien n'est cree, l'existante est intacte")
    void create_blockConflictLeavesDatabaseUntouched() {
        Long subnetId = insertSubnet("10.10.10.0/24", null);
        insertAddress(subnetId, "10.10.10.22", "existing-host");

        assertThatThrownBy(() -> service.create(request(subnetId, "10.10.10.21", 3, null), "nahim"))
                .isInstanceOf(SeriesConflictException.class);

        assertThat(storedAddresses(subnetId))
                .extracting(row -> row.get("ip"), row -> row.get("hostname"))
                .containsExactly(tuple("10.10.10.22", "existing-host"));
    }

    @Test
    @DisplayName("create : mode skip saute l'adresse occupee et la passerelle")
    void create_skipModeSkipsOccupiedAndGateway() {
        Long subnetId = insertSubnet("10.10.10.0/24", "10.10.10.1");
        insertAddress(subnetId, "10.10.10.2", "existing-host");

        SeriesReservationResponse response = service.create(
                request(subnetId, "10.10.10.1", 2, "skip"), "nahim");

        assertThat(response.skipped()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.1", "10.10.10.2");
        assertThat(storedAddresses(subnetId))
                .extracting(row -> row.get("ip"), row -> row.get("hostname"))
                .containsExactly(
                        tuple("10.10.10.2", "existing-host"),
                        tuple("10.10.10.3", "NTNX-HOST-01"),
                        tuple("10.10.10.4", "NTNX-HOST-02"));
    }

    @Test
    @DisplayName("preview : detecte les adresses occupees (bornes incluses) sans rien ecrire")
    void preview_detectsOccupiedAddressesInclusiveBounds() {
        Long subnetId = insertSubnet("10.10.10.0/24", null);
        insertAddress(subnetId, "10.10.10.21", "first");
        insertAddress(subnetId, "10.10.10.23", "last");

        SeriesReservationResponse response = service.preview(request(subnetId, "10.10.10.21", 3, null));

        assertThat(response.canCreate()).isFalse();
        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("OCCUPIED", "AVAILABLE", "OCCUPIED");
        assertThat(storedAddresses(subnetId)).hasSize(2);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private static SeriesReservationRequest request(Long subnetId, String startIp, int count,
                                                    String occupiedMode) {
        return new SeriesReservationRequest(
                subnetId, startIp, count, null, "NTNX-HOST-", null, null, null, occupiedMode, null, null);
    }

    private List<Map<String, Object>> storedAddresses(Long subnetId) {
        return jdbcTemplate.queryForList(
                """
                SELECT host(address) AS ip, hostname, modified_by, discovery_source
                FROM addresses
                WHERE subnet_id = ?
                ORDER BY address
                """,
                subnetId);
    }

    private Long insertSubnet(String network, String gateway) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO subnets (network, gateway, context_id, site_id, description)
                VALUES (CAST(? AS cidr), CAST(? AS inet), ?, ?, 'Series test')
                RETURNING id
                """,
                Long.class,
                network,
                gateway,
                contextId,
                siteId);
    }

    private void insertAddress(Long subnetId, String address, String hostname) {
        jdbcTemplate.update(
                """
                INSERT INTO addresses (
                    address, hostname, context_id, site_id, subnet_id,
                    modified_by, discovery_source
                )
                VALUES (CAST(? AS inet), ?, ?, ?, ?, 'test', 'manual')
                """,
                address,
                hostname,
                contextId,
                siteId,
                subnetId);
    }
}
