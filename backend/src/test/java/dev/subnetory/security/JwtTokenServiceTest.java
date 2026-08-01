package dev.subnetory.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-minimum-32-characters-for-hs256";

    @Test
    void generateToken_addsUniqueJtiClaim() {
        SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        JwtTokenService service = new JwtTokenService(encoder, 60);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        Jwt first = decoder.decode(service.generateToken(authentication));
        Jwt second = decoder.decode(service.generateToken(authentication));

        assertNotNull(first.getId());
        assertNotNull(second.getId());
        assertDoesNotThrow(() -> UUID.fromString(first.getId()));
        assertDoesNotThrow(() -> UUID.fromString(second.getId()));
        assertNotEquals(first.getId(), second.getId());
    }
}
