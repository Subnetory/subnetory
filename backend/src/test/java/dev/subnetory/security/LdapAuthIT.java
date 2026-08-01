package dev.subnetory.security;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFException;
import dev.subnetory.domain.Role;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intÃ©gration LDAP â€” Sprint 2.3.
 *
 * Infrastructure de test :
 *  - PostgreSQL via Testcontainers (mÃªme pattern que les autres *IT)
 *  - Serveur LDAP in-process via UnboundID SDK (aucun serveur externe requis)
 *  - LDAP activÃ© pour ce test via @DynamicPropertySource
 *    (contexte Spring distinct des 234 tests standard qui ont LDAP dÃ©sactivÃ©)
 *
 * Annuaire LDAP de test â€” dc=subnetory,dc=test / ou=users :
 *   uid=jdoe     / jdoe-secret      â†’ tests auth basiques
 *   uid=jane     / jane-secret      â†’ test second login + no-duplicate
 *   uid=dbuser1  / dbuser1-secret   â†’ test assertions DB auto-provisioning
 *   uid=admin    / ldap-admin-pass  â†’ test collision avec compte LOCAL admin
 *
 * Gestionnaire : cn=manager,dc=subnetory,dc=test / ldap-manager-secret
 *
 * DÃ©marrage LDAP via ensureLdapStarted() appelÃ© depuis @DynamicPropertySource
 * pour garantir que le serveur est disponible avant le chargement du contexte Spring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class LdapAuthIT {

    // â”€â”€ Infrastructure â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    static InMemoryDirectoryServer ldapServer;

    /**
     * DÃ©marre le serveur LDAP embarquÃ© de faÃ§on synchronisÃ©e.
     * AppelÃ© depuis @DynamicPropertySource pour garantir que ldapServer
     * est initialisÃ© avant que Spring charge le contexte.
     * Sans cette garantie, ldapServer serait null lors de l'Ã©valuation
     * des lambdas dans @DynamicPropertySource.
     */
    static synchronized void ensureLdapStarted() {
        if (ldapServer != null) {
            return;
        }
        try {
            InMemoryDirectoryServerConfig config =
                    new InMemoryDirectoryServerConfig("dc=subnetory,dc=test");
            config.addAdditionalBindCredentials(
                    "cn=manager,dc=subnetory,dc=test", "ldap-manager-secret");

            ldapServer = new InMemoryDirectoryServer(config);

            // Arborescence
            ldapServer.add(
                    "dn: dc=subnetory,dc=test",
                    "objectClass: top", "objectClass: domain", "dc: subnetory");
            ldapServer.add(
                    "dn: ou=users,dc=subnetory,dc=test",
                    "objectClass: top", "objectClass: organizationalUnit", "ou: users");

            // jdoe â€” tests auth basiques
            ldapServer.add(
                    "dn: uid=jdoe,ou=users,dc=subnetory,dc=test",
                    "objectClass: inetOrgPerson",
                    "uid: jdoe", "cn: John Doe", "sn: Doe",
                    "userPassword: jdoe-secret");

            // jane â€” test no-duplicate
            ldapServer.add(
                    "dn: uid=jane,ou=users,dc=subnetory,dc=test",
                    "objectClass: inetOrgPerson",
                    "uid: jane", "cn: Jane Smith", "sn: Smith",
                    "userPassword: jane-secret");

            // dbuser1 â€” test assertions DB
            ldapServer.add(
                    "dn: uid=dbuser1,ou=users,dc=subnetory,dc=test",
                    "objectClass: inetOrgPerson",
                    "uid: dbuser1", "cn: DB User One", "sn: One",
                    "userPassword: dbuser1-secret");

            // admin (LDAP) â€” mÃªme username que le compte LOCAL admin
            // Permet de tester la vraie collision : bind LDAP rÃ©ussit,
            // mais LdapUserProvisioningService doit rejeter car auth_type=LOCAL en DB.
            ldapServer.add(
                    "dn: uid=admin,ou=users,dc=subnetory,dc=test",
                    "objectClass: inetOrgPerson",
                    "uid: admin", "cn: Admin LDAP", "sn: Admin",
                    "userPassword: ldap-admin-pass");

            ldapServer.startListening();
        } catch (LDAPException | LDIFException e) {
            throw new RuntimeException("Failed to start embedded LDAP server", e);
        }
    }

    @AfterAll
    static void stopLdap() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // ensureLdapStarted() AVANT toute rÃ©fÃ©rence Ã  ldapServer
        ensureLdapStarted();

        // PostgreSQL â€” &stringtype=unspecified requis pour les types PostgreSQL natifs (inet, macaddr)
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);

        // LDAP â€” configuration de démarrage utilisée par le provider dynamique
        registry.add("subnetory.auth.ldap.enabled",
                () -> "true");
        registry.add("subnetory.auth.ldap.url",
                () -> "ldap://localhost:" + ldapServer.getListenPort());
        registry.add("subnetory.auth.ldap.base-dn",
                () -> "dc=subnetory,dc=test");
        registry.add("subnetory.auth.ldap.user-search-base",
                () -> "ou=users");
        registry.add("subnetory.auth.ldap.user-search-filter",
                () -> "(uid={0})");
        registry.add("subnetory.auth.ldap.manager-dn",
                () -> "cn=manager,dc=subnetory,dc=test");
        registry.add("subnetory.auth.ldap.manager-password",
                () -> "ldap-manager-secret");
        registry.add("subnetory.auth.ldap.default-role",
                () -> "ROLE_IP");
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;

    // â”€â”€ Authentification LDAP basique â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void ldapUser_validCredentials_redirectsToDefaultSuccessUrl() throws Exception {
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "jdoe")
                        .param("password", "jdoe-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void ldapUser_invalidPassword_redirectsToLoginError() throws Exception {
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "jdoe")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void ldapUser_unknownUsername_redirectsToLoginError() throws Exception {
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "nobody")
                        .param("password", "anything"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void ldapLogin_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/login")
                        .param("username", "jdoe")
                        .param("password", "jdoe-secret"))
                .andExpect(status().isForbidden());
    }

    // â”€â”€ Auto-provisioning â€” assertions en base â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void ldapUser_firstLogin_provisionedInDatabase() throws Exception {
        // dbuser1 dÃ©diÃ© Ã  ce test pour Ã©viter les interfÃ©rences d'ordre d'exÃ©cution
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "dbuser1")
                        .param("password", "dbuser1-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        var provisioned = userRepository.findByUsername("dbuser1").orElseThrow();
        assertThat(provisioned.getAuthType()).isEqualTo("LDAP");
        assertThat(provisioned.getPassword()).isNull();
        assertThat(provisioned.isEnabled()).isTrue();
        assertThat(provisioned.getRoles())
                .extracting(Role::getName)
                .containsExactly("ROLE_IP");
    }

    @Test
    void ldapUser_secondLogin_doesNotDuplicateUserInDatabase() throws Exception {
        // Premier login â€” jane dÃ©diÃ©e Ã  ce test
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "jane")
                        .param("password", "jane-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        long countAfterFirst = userRepository.findAll().stream()
                .filter(u -> "jane".equals(u.getUsername()))
                .count();
        assertThat(countAfterFirst).isEqualTo(1);

        // DeuxiÃ¨me login â€” ne doit pas crÃ©er de doublon
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "jane")
                        .param("password", "jane-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        long countAfterSecond = userRepository.findAll().stream()
                .filter(u -> "jane".equals(u.getUsername()))
                .count();
        assertThat(countAfterSecond).isEqualTo(1);
    }

    // â”€â”€ Collision LOCAL â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Vrai test de collision : 'admin' existe en LDAP (avec mot de passe ldap-admin-pass)
     * ET en DB avec auth_type=LOCAL.
     *
     * Flux :
     * 1. LOCAL provider : loadUserByUsername("admin") rÃ©ussit, BCrypt ne correspond pas â†’ passe au suivant
     * 2. LDAP provider : bind uid=admin RÃ‰USSIT (le mot de passe LDAP est correct)
     * 3. LdapUserProvisioningService : trouve admin en DB avec auth_type=LOCAL â†’ BadCredentialsException
     * â†’ /login?error
     *
     * Ce test valide que la protection cÃ´tÃ© mapper empÃªche l'usurpation d'un compte LOCAL via LDAP,
     * mÃªme quand le bind LDAP rÃ©ussit.
     */
    @Test
    void ldapUser_collidesWithLocalAccount_ldapLoginBlocked() throws Exception {
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "ldap-admin-pass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        // Le compte admin LOCAL en DB est intact â€” auth_type toujours LOCAL
        var admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getAuthType()).isEqualTo("LOCAL");
        assertThat(admin.getPassword()).isNotNull();
    }

    // â”€â”€ Compte admin LOCAL toujours fonctionnel â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void localAdmin_unaffectedByLdap_canStillLogin() throws Exception {
        // Le mot de passe admin est initialisÃ© depuis application-test.yml (default: admin)
        mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // â”€â”€ AccÃ¨s anonyme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void anonymousUser_accessingProtectedPage_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/subnets"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}

