package dev.subnetory.web;

/**
 * View model pour une ligne de la liste des subnets.
 * Calculé dans SubnetWebController, contient le champ scannable par ligne.
 */
public record SubnetRowView(
        Long id,
        String network,
        String description,
        String gateway,
        String vlanName,
        String siteName,
        String contextName,
        boolean scannable
) {}
