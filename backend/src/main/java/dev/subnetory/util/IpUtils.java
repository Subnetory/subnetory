package dev.subnetory.util;

import org.apache.commons.net.util.SubnetUtils;
import java.util.regex.Pattern;

/**
 * Utilitaires IP v4. Remplace IPFmt.java de la v1 sans le format zéro-paddé.
 * S'appuie sur Apache commons-net pour la logique CIDR (testé, mature).
 *
 * <p>Toutes les méthodes acceptent et retournent des chaînes au format standard
 * ("192.168.1.10", "192.168.1.0/24", "aa:bb:cc:dd:ee:ff"). La validation est
 * exhaustive — toute entrée invalide lève IllegalArgumentException.
 */
public final class IpUtils {

    private static final Pattern IPV4 = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    private static final Pattern CIDR = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/([0-9]|[12][0-9]|3[0-2])$"
    );

    private static final Pattern MAC = Pattern.compile(
        "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$"
    );

    private IpUtils() {}

    /** Vérifie qu'une chaîne est une IPv4 valide (sans préfixe). */
    public static boolean isValidIpv4(String ip) {
        return ip != null && IPV4.matcher(ip).matches();
    }

    /** Vérifie qu'une chaîne est un CIDR IPv4 valide (ex: 192.168.1.0/24). */
    public static boolean isValidCidr(String cidr) {
        return cidr != null && CIDR.matcher(cidr).matches();
    }

    /** Vérifie qu'une chaîne est une adresse MAC valide. */
    public static boolean isValidMac(String mac) {
        return mac != null && MAC.matcher(mac).matches();
    }

    /** Normalise une MAC en minuscules séparées par ":". */
    public static String normalizeMac(String mac) {
        requireValidMac(mac);
        return mac.replace("-", ":").toLowerCase();
    }

    /** Adresse réseau d'un CIDR. "192.168.1.10/24" -> "192.168.1.0". */
    public static String networkAddress(String cidr) {
        return info(cidr).getNetworkAddress();
    }

    /** Broadcast d'un CIDR. "192.168.1.10/24" -> "192.168.1.255". */
    public static String broadcastAddress(String cidr) {
        return info(cidr).getBroadcastAddress();
    }

    /** Test d'appartenance : "192.168.1.10" appartient-il à "192.168.1.0/24" ? */
    public static boolean isInNetwork(String ip, String cidr) {
        requireValidIp(ip);
        SubnetUtils.SubnetInfo info = info(cidr);
        return info.isInRange(ip)
            || ip.equals(info.getNetworkAddress())
            || ip.equals(info.getBroadcastAddress());
    }

    /** Nombre total d'adresses utilisables (exclut network et broadcast pour /24 et moins). */
    public static long usableAddressCount(String cidr) {
        return info(cidr).getAddressCountLong();
    }

    /** Retourne la longueur de préfixe d'un CIDR IPv4 valide. */
    public static int cidrPrefixLength(String cidr) {
        if (!isValidCidr(cidr)) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }
        return Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
    }

    /** Convertit une IPv4 en entier non signé stocké dans un long. */
    public static long ipv4ToLong(String ip) {
        requireValidIp(ip);
        String[] octets = ip.split("\\.");
        return (Long.parseLong(octets[0]) << 24)
             | (Long.parseLong(octets[1]) << 16)
             | (Long.parseLong(octets[2]) << 8)
             | Long.parseLong(octets[3]);
    }

    /** Convertit un entier non signé IPv4 stocké dans un long en notation dotted-decimal. */
    public static String longToIpv4(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("IPv4 numeric value out of range: " + value);
        }
        return ((value >> 24) & 0xFF) + "." +
               ((value >> 16) & 0xFF) + "." +
               ((value >> 8) & 0xFF) + "." +
               (value & 0xFF);
    }

    /** Incrémente une IP : "192.168.1.10" -> "192.168.1.11". */
    public static String increment(String ip) {
        long value = ipv4ToLong(ip) + 1;
        if (value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("IP overflow: " + ip);
        }
        return longToIpv4(value);
    }

    /**
     * Suggère le prochain hostname à partir d'un préfixe et d'un nom existant.
     * Exemple : prefix="srv-web", lastName="srv-web-007" -> "srv-web-008".
     * Si lastName est null, retourne prefix + "-001".
     */
    public static String suggestNextHostname(String prefix, String lastName) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        if (lastName == null || !lastName.startsWith(prefix)) {
            return prefix + "-001";
        }
        // Extrait le suffixe numérique éventuel
        String suffix = lastName.substring(prefix.length()).replaceFirst("^[^0-9]*", "");
        try {
            int n = Integer.parseInt(suffix);
            return String.format("%s-%03d", prefix, n + 1);
        } catch (NumberFormatException e) {
            return prefix + "-001";
        }
    }

    // ---- privé ----

    private static SubnetUtils.SubnetInfo info(String cidr) {
        if (!isValidCidr(cidr)) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }
        SubnetUtils utils = new SubnetUtils(cidr);
        utils.setInclusiveHostCount(true); // network + broadcast comptés comme utilisables
        return utils.getInfo();
    }

    private static void requireValidIp(String ip) {
        if (!isValidIpv4(ip)) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ip);
        }
    }

    private static void requireValidMac(String mac) {
        if (!isValidMac(mac)) {
            throw new IllegalArgumentException("Invalid MAC address: " + mac);
        }
    }
}
