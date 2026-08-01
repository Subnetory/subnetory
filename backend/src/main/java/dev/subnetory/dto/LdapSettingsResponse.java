package dev.subnetory.dto;

import java.util.List;

public record LdapSettingsResponse(
        boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        String userSearchFilter,
        boolean managerDnConfigured,
        boolean managerPasswordConfigured,
        List<String> defaultRoles,
        String defaultRole
) {}
