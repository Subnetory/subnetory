package dev.subnetory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
class UserTokenInvalidationRepositoryIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserTokenInvalidationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM user_token_invalidations");
        // "admin" est seede par V2__seed_data.sql et reste ; tout autre
        // utilisateur ajoute par un test precedent (cf. tests
        // upsertNotBeforeForAllUsers_*) est retire pour que chaque test
        // reste deterministe independamment de l'ordre d'execution.
        jdbcTemplate.update("DELETE FROM users WHERE username <> 'admin'");
    }

    @Test
    void insertsInvalidationThreshold() {
        OffsetDateTime notBefore = OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC);

        int rows = repository.upsertNotBefore("alice", notBefore, "alice", "LOGOUT_ALL");

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findNotBeforeByUsername("alice"))
                .isPresent()
                .get()
                .isEqualTo(notBefore.toInstant());
    }

    @Test
    void updatesExistingInvalidationThreshold() {
        OffsetDateTime first = OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime second = OffsetDateTime.of(2026, 7, 7, 16, 0, 0, 0, ZoneOffset.UTC);

        repository.upsertNotBefore("alice", first, "alice", "LOGOUT_ALL");
        int rows = repository.upsertNotBefore("alice", second, "admin", "ADMIN_REVOKE");

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findNotBeforeByUsername("alice"))
                .isPresent()
                .get()
                .isEqualTo(second.toInstant());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_token_invalidations WHERE username = ?",
                Integer.class,
                "alice");
        String invalidatedBy = jdbcTemplate.queryForObject(
                "SELECT invalidated_by FROM user_token_invalidations WHERE username = ?",
                String.class,
                "alice");
        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM user_token_invalidations WHERE username = ?",
                String.class,
                "alice");

        assertThat(count).isEqualTo(1);
        assertThat(invalidatedBy).isEqualTo("admin");
        assertThat(reason).isEqualTo("ADMIN_REVOKE");
    }

    // -------------------------------------------------------
    // Invalidation globale post-restauration (correctif securite MOYENNE,
    // audit 04/08/2026)
    // -------------------------------------------------------

    @Test
    void upsertNotBeforeForAllUsers_insertsOneRowPerUser() {
        // "admin" existe deja via V2__seed_data.sql ; un second utilisateur
        // est insere directement pour prouver que la requete couvre bien
        // TOUS les utilisateurs, pas seulement le premier de la table.
        jdbcTemplate.update(
                "INSERT INTO users (username, password, email, auth_type, enabled) "
                        + "VALUES ('bob', NULL, 'bob@subnetory.local', 'LOCAL', true)");
        OffsetDateTime notBefore = OffsetDateTime.of(2026, 8, 4, 12, 0, 0, 0, ZoneOffset.UTC);

        int rows = repository.upsertNotBeforeForAllUsers(notBefore, "SYSTEM", "POST_RESTORE");

        assertThat(rows).isEqualTo(2);
        assertThat(repository.findNotBeforeByUsername("admin"))
                .isPresent().get().isEqualTo(notBefore.toInstant());
        assertThat(repository.findNotBeforeByUsername("bob"))
                .isPresent().get().isEqualTo(notBefore.toInstant());
    }

    @Test
    void upsertNotBeforeForAllUsers_overwritesExistingPerUserThreshold() {
        OffsetDateTime earlier = OffsetDateTime.of(2026, 8, 4, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime later = OffsetDateTime.of(2026, 8, 4, 13, 0, 0, 0, ZoneOffset.UTC);
        repository.upsertNotBefore("admin", earlier, "admin", "LOGOUT_ALL");

        int rows = repository.upsertNotBeforeForAllUsers(later, "SYSTEM", "POST_RESTORE");

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findNotBeforeByUsername("admin"))
                .isPresent().get().isEqualTo(later.toInstant());
        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM user_token_invalidations WHERE username = ?",
                String.class,
                "admin");
        assertThat(reason).isEqualTo("POST_RESTORE");
    }
}
