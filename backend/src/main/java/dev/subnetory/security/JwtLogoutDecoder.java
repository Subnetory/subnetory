package dev.subnetory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Decodeur JWT utilise uniquement par POST /api/v1/auth/logout.
 *
 * <p>Le logout est volontairement decode sans le validateur de revocation afin
 * de rester idempotent : un second logout avec le meme token revoque doit encore
 * retourner 204. Tous les autres endpoints API passent par le JwtDecoder standard
 * qui applique la denylist.</p>
 */
@Component
public class JwtLogoutDecoder {

    private final JwtDecoder decoder;

    public JwtLogoutDecoder(@Value("${subnetory.jwt.secret}") String jwtSecret) {
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
    }

    public Jwt decode(String token) {
        return decoder.decode(token);
    }
}
