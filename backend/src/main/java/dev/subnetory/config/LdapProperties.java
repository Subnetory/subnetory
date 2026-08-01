package dev.subnetory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés LDAP Subnetory — section subnetory.auth.ldap.
 *
 * Valeurs de démarrage utilisées comme configuration par défaut.
 * La configuration active peut ensuite être gérée depuis l'administration
 * graphique ou l'API admin.
 */
@ConfigurationProperties(prefix = "subnetory.auth.ldap")
public class LdapProperties {

    /** Active ou désactive l'authentification LDAP. Défaut : false. */
    private boolean enabled = false;

    /**
     * URL du serveur LDAP.
     * Exemples : ldap://ldap.example.com:389  |  ldaps://ldap.example.com:636
     */
    private String url = "ldap://localhost:389";

    /**
     * DN de base pour les recherches.
     * Exemple : dc=example,dc=com
     */
    private String baseDn = "dc=example,dc=com";

    /**
     * Base de recherche des utilisateurs (relatif au baseDn).
     * Exemple : ou=users
     */
    private String userSearchBase = "ou=users";

    /**
     * Filtre de recherche. {0} est remplacé par le username saisi.
     * Active Directory : (sAMAccountName={0})
     * OpenLDAP         : (uid={0})
     * Email comme login: (mail={0})
     */
    private String userSearchFilter = "(sAMAccountName={0})";

    /**
     * DN du compte de service pour les recherches (manager bind).
     * Obligatoire en pratique sur Active Directory.
     * Configurer via SUBNETORY_LDAP_MANAGER_DN.
     */
    private String managerDn = "";

    /**
     * Mot de passe du compte de service.
     * Ne jamais mettre une valeur réelle dans application.yml.
     * Configurer via SUBNETORY_LDAP_MANAGER_PASSWORD.
     */
    private String managerPassword = "";

    /**
     * Rôle Subnetory assigné automatiquement à la première connexion LDAP.
     * Doit correspondre à un rôle existant dans la table roles.
     * Défaut : ROLE_IP (accès limité — l'élévation est manuelle via l'admin).
     */
    private String defaultRole = "ROLE_IP";

    // ── Getters / Setters ──────────────────────────────────────────────────

    public boolean isEnabled()                   { return enabled; }
    public void setEnabled(boolean enabled)      { this.enabled = enabled; }

    public String getUrl()                       { return url; }
    public void setUrl(String url)               { this.url = url; }

    public String getBaseDn()                    { return baseDn; }
    public void setBaseDn(String baseDn)         { this.baseDn = baseDn; }

    public String getUserSearchBase()                        { return userSearchBase; }
    public void setUserSearchBase(String userSearchBase)     { this.userSearchBase = userSearchBase; }

    public String getUserSearchFilter()                      { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }

    public String getManagerDn()                 { return managerDn; }
    public void setManagerDn(String managerDn)   { this.managerDn = managerDn; }

    public String getManagerPassword()                       { return managerPassword; }
    public void setManagerPassword(String managerPassword)   { this.managerPassword = managerPassword; }

    public String getDefaultRole()                           { return defaultRole; }
    public void setDefaultRole(String defaultRole)           { this.defaultRole = defaultRole; }
}
