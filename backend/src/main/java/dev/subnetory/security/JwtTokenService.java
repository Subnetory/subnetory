package dev.subnetory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Émet des JWT signés HS256. La validation est gérée par Spring Security
 * via le resource server (JwtDecoder configuré dans SecurityConfig).
 *
 * <p>L'algorithme est rendu explicite via JwsHeader pour éviter toute
 * dépendance vis-à-vis de l'inférence automatique de NimbusJwtEncoder
 * (qui pourrait changer entre versions de Spring Security).
 */
@Service
public class JwtTokenService {

    private static final JwsHeader HS256_HEADER =
        JwsHeader.with(MacAlgorithm.HS256).build();

    private final JwtEncoder encoder;
    private final long expirationMinutes;

    public JwtTokenService(JwtEncoder encoder,
                           @Value("${subnetory.jwt.expiration-minutes:60}") long expirationMinutes) {
        this.encoder = encoder;
        this.expirationMinutes = expirationMinutes;
    }

    public String generateToken(Authentication authentication) {
        Instant now = Instant.now();
        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("subnetory")
            .issuedAt(now)
            .expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))
            .id(UUID.randomUUID().toString())
            .subject(authentication.getName())
            .claim("roles", roles)
            .build();

        return encoder.encode(JwtEncoderParameters.from(HS256_HEADER, claims)).getTokenValue();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }
}
