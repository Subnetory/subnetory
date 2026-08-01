package dev.subnetory.dto;

import java.util.List;

/**
 * Agrégat de statistiques globales affiché sur le dashboard.
 *
 * <p>Calculé en lecture seule depuis les 5 tables principales. Aucune
 * modification de schéma — aucune migration Flyway requise.</p>
 *
 * @param totalContexts  nombre de contextes de routage (VRF)
 * @param totalSites     nombre de sites
 * @param totalVlans     nombre de VLANs
 * @param totalSubnets   nombre de sous-réseaux
 * @param totalAddresses nombre d'adresses IP enregistrées
 * @param topSubnets     top 10 subnets triés par taux d'utilisation décroissant
 */
public record DashboardStats(
        long totalContexts,
        long totalSites,
        long totalVlans,
        long totalSubnets,
        long totalAddresses,
        List<SubnetUtilizationEntry> topSubnets
) {}
