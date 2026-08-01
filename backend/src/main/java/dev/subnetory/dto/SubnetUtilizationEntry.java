package dev.subnetory.dto;

/**
 * Statistiques d'utilisation d'un sous-réseau.
 *
 * <p>{@code capacity} est calculé via {@link dev.subnetory.util.IpUtils#usableAddressCount}
 * avec {@code inclusiveHostCount=true} — inclut l'adresse réseau et le broadcast.
 * La valeur est donc le nombre total d'adresses dans le bloc CIDR.</p>
 *
 * <p>{@code available} est borné à 0 par {@code max(0, capacity - used)} pour rester
 * cohérent si des données incohérentes existent en base (used > capacity).</p>
 *
 * <p>{@code utilizationPct} est borné à 100 pour la même raison.</p>
 *
 * @param subnetId       identifiant du sous-réseau
 * @param network        réseau CIDR (ex: {@code 10.0.0.0/24})
 * @param description    description (nullable)
 * @param siteName       nom du site parent
 * @param contextName    nom du contexte de routage parent
 * @param capacity       capacité théorique (adresses dans le bloc CIDR)
 * @param used           adresses IP enregistrées dans ce subnet
 * @param available      adresses disponibles = {@code max(0, capacity - used)}
 * @param utilizationPct taux d'utilisation en % [0..100]
 */
public record SubnetUtilizationEntry(
        long   subnetId,
        String network,
        String description,
        String siteName,
        String contextName,
        long   capacity,
        long   used,
        long   available,
        int    utilizationPct
) {}
