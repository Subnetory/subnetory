package dev.subnetory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

public record LdapSettingsRequest(
        boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        @Schema(description = "Filtre LDAP de recherche utilisateur. La valeur {0} est remplacée par l'identifiant saisi à la connexion.",
                example = "(sAMAccountName={0})")
        String userSearchFilter,
        String managerDn,
        String managerPassword,
        boolean clearManagerPassword,
        @Schema(description = "Rôles attribués à la première connexion LDAP. Utiliser ROLE_READ_ONLY pour un compte de consultation, ou ROLE_NETWORK et ROLE_IP pour un exploitant réseau complet sans accès administration.",
                allowableValues = {"ROLE_READ_ONLY", "ROLE_IP", "ROLE_NETWORK", "ROLE_ADMIN"},
                example = "[\"ROLE_NETWORK\", \"ROLE_IP\"]")
        Set<String> defaultRoles,
        @Schema(description = "Compatibilité anciens clients. Préférer defaultRoles pour attribuer plusieurs rôles LDAP.",
                allowableValues = {"ROLE_READ_ONLY", "ROLE_IP", "ROLE_NETWORK", "ROLE_ADMIN"},
                example = "ROLE_IP")
        String defaultRole
) {}
