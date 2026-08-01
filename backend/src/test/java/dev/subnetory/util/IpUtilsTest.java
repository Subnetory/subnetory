package dev.subnetory.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IpUtilsTest {

    @Test
    void isValidIpv4_acceptsValidAddresses() {
        assertTrue(IpUtils.isValidIpv4("192.168.1.10"));
        assertTrue(IpUtils.isValidIpv4("0.0.0.0"));
        assertTrue(IpUtils.isValidIpv4("255.255.255.255"));
        assertTrue(IpUtils.isValidIpv4("10.0.0.1"));
    }

    @Test
    void isValidIpv4_rejectsInvalidAddresses() {
        assertFalse(IpUtils.isValidIpv4(null));
        assertFalse(IpUtils.isValidIpv4(""));
        assertFalse(IpUtils.isValidIpv4("256.0.0.1"));
        assertFalse(IpUtils.isValidIpv4("192.168.1"));
        assertFalse(IpUtils.isValidIpv4("192.168.1.10/24"));
        assertFalse(IpUtils.isValidIpv4("not-an-ip"));
    }

    @Test
    void isValidCidr_acceptsValidNotation() {
        assertTrue(IpUtils.isValidCidr("192.168.1.0/24"));
        assertTrue(IpUtils.isValidCidr("10.0.0.0/8"));
        assertTrue(IpUtils.isValidCidr("0.0.0.0/0"));
        assertTrue(IpUtils.isValidCidr("192.168.1.0/32"));
    }

    @Test
    void isValidCidr_rejectsInvalid() {
        assertFalse(IpUtils.isValidCidr("192.168.1.0"));
        assertFalse(IpUtils.isValidCidr("192.168.1.0/33"));
        assertFalse(IpUtils.isValidCidr("192.168.1.0/-1"));
        assertFalse(IpUtils.isValidCidr(null));
    }

    @Test
    void isValidMac_acceptsBothSeparators() {
        assertTrue(IpUtils.isValidMac("aa:bb:cc:dd:ee:ff"));
        assertTrue(IpUtils.isValidMac("AA:BB:CC:DD:EE:FF"));
        assertTrue(IpUtils.isValidMac("aa-bb-cc-dd-ee-ff"));
    }

    @Test
    void isValidMac_rejectsInvalid() {
        assertFalse(IpUtils.isValidMac("aabbccddeeff"));     // pas de séparateur
        assertFalse(IpUtils.isValidMac("aa:bb:cc:dd:ee"));   // trop court
        assertFalse(IpUtils.isValidMac("zz:bb:cc:dd:ee:ff")); // chars invalides
        assertFalse(IpUtils.isValidMac(null));
    }

    @Test
    void normalizeMac_returnsLowerCaseColonSeparated() {
        assertEquals("aa:bb:cc:dd:ee:ff", IpUtils.normalizeMac("AA-BB-CC-DD-EE-FF"));
        assertEquals("aa:bb:cc:dd:ee:ff", IpUtils.normalizeMac("aa:bb:cc:dd:ee:ff"));
    }

    @Test
    void networkAddress_returnsCorrectNetwork() {
        assertEquals("192.168.1.0",  IpUtils.networkAddress("192.168.1.10/24"));
        assertEquals("10.0.0.0",     IpUtils.networkAddress("10.5.7.42/8"));
        assertEquals("192.168.1.0",  IpUtils.networkAddress("192.168.1.127/25"));
        assertEquals("192.168.1.128", IpUtils.networkAddress("192.168.1.200/25"));
    }

    @Test
    void broadcastAddress_returnsCorrectBroadcast() {
        assertEquals("192.168.1.255",   IpUtils.broadcastAddress("192.168.1.10/24"));
        assertEquals("10.255.255.255",  IpUtils.broadcastAddress("10.0.0.1/8"));
        assertEquals("192.168.1.127",   IpUtils.broadcastAddress("192.168.1.10/25"));
    }

    @Test
    void isInNetwork_returnsTrueForMembersAndBoundaries() {
        assertTrue(IpUtils.isInNetwork("192.168.1.10", "192.168.1.0/24"));
        assertTrue(IpUtils.isInNetwork("192.168.1.0", "192.168.1.0/24"));   // network address
        assertTrue(IpUtils.isInNetwork("192.168.1.255", "192.168.1.0/24")); // broadcast
        assertTrue(IpUtils.isInNetwork("10.50.60.70", "10.0.0.0/8"));
    }

    @Test
    void isInNetwork_returnsFalseForNonMembers() {
        assertFalse(IpUtils.isInNetwork("192.168.2.10", "192.168.1.0/24"));
        assertFalse(IpUtils.isInNetwork("10.0.0.1", "192.168.0.0/16"));
    }

    @Test
    void increment_addsOne() {
        assertEquals("192.168.1.11",  IpUtils.increment("192.168.1.10"));
        assertEquals("192.168.2.0",   IpUtils.increment("192.168.1.255"));
        assertEquals("10.0.0.1",      IpUtils.increment("10.0.0.0"));
    }

    @Test
    void increment_overflowThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> IpUtils.increment("255.255.255.255"));
    }

    @Test
    void increment_rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
            () -> IpUtils.increment("not-an-ip"));
    }

    @Test
    void suggestNextHostname_incrementsExistingNumber() {
        assertEquals("srv-web-008", IpUtils.suggestNextHostname("srv-web", "srv-web-007"));
        assertEquals("srv-web-100", IpUtils.suggestNextHostname("srv-web", "srv-web-099"));
    }

    @Test
    void suggestNextHostname_startsAt001IfNoExisting() {
        assertEquals("srv-web-001", IpUtils.suggestNextHostname("srv-web", null));
        assertEquals("srv-web-001", IpUtils.suggestNextHostname("srv-web", "other-007"));
    }

    @Test
    void suggestNextHostname_rejectsBlankPrefix() {
        assertThrows(IllegalArgumentException.class,
            () -> IpUtils.suggestNextHostname("", null));
        assertThrows(IllegalArgumentException.class,
            () -> IpUtils.suggestNextHostname(null, null));
    }

    @Test
    void usableAddressCount_isCorrect() {
        // /24 inclusive = 256 adresses (network + broadcast comptés)
        assertEquals(256, IpUtils.usableAddressCount("192.168.1.0/24"));
        // /30 inclusive = 4 adresses
        assertEquals(4, IpUtils.usableAddressCount("192.168.1.0/30"));
        // /32 inclusive = 1 adresse
        assertEquals(1, IpUtils.usableAddressCount("192.168.1.1/32"));
    }
    @Test
    void cidrPrefixLength_returnsExpectedPrefix() {
        assertEquals(24, IpUtils.cidrPrefixLength("192.168.1.0/24"));
        assertEquals(31, IpUtils.cidrPrefixLength("192.0.2.10/31"));
        assertEquals(32, IpUtils.cidrPrefixLength("192.0.2.42/32"));
    }

    @Test
    void ipv4LongConversion_roundTrip() {
        assertEquals(0L, IpUtils.ipv4ToLong("0.0.0.0"));
        assertEquals(0xFFFFFFFFL, IpUtils.ipv4ToLong("255.255.255.255"));
        assertEquals("192.0.2.42", IpUtils.longToIpv4(IpUtils.ipv4ToLong("192.0.2.42")));
    }

}
