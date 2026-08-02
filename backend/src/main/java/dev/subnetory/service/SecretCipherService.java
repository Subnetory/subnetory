package dev.subnetory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretCipherService {

    private static final Logger log = LoggerFactory.getLogger(SecretCipherService.class);

    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    // Cle dediee (audit 02/08/2026, correctif ELEVEE) : jusqu'ici cette cle
    // etait derivee du secret JWT (subnetory.jwt.secret), utilise par
    // ailleurs pour signer/verifier les jetons d'authentification. Reutiliser
    // le meme secret pour deux usages distincts (signature JWT vs chiffrement
    // au repos du mot de passe de bind LDAP et du secret TOTP MFA) melange
    // deux domaines de securite : la compromission de l'un compromet l'autre,
    // et une rotation legitime du secret JWT (ex. suite a un incident) rend
    // alors illisibles les secrets deja chiffres en base, sans lien logique
    // avec l'incident JWT. subnetory.security.encryption-key est desormais
    // la source recommandee. Repli sur subnetory.jwt.secret si absente, pour
    // ne pas casser une instance existante qui n'a pas encore ce secret
    // (voir scripts/init-compose.sh/.ps1 et charts/subnetory pour la
    // procedure de migration) — avec avertissement au demarrage.
    public SecretCipherService(
            @Value("${subnetory.security.encryption-key:}") String encryptionKey,
            @Value("${subnetory.jwt.secret}") String jwtSecret) {
        String secret;
        if (encryptionKey != null && !encryptionKey.isBlank()) {
            secret = encryptionKey;
        } else {
            secret = jwtSecret;
            log.warn("subnetory.security.encryption-key n'est pas configuree : repli sur "
                    + "subnetory.jwt.secret pour chiffrer les secrets stockes (mot de passe LDAP, "
                    + "secret MFA). Ce repli reste fonctionnel mais n'est pas recommande : "
                    + "configurer une cle dediee (voir backend/docs/ADMIN_GUIDE.md).");
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Secret encryption failed.", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return "";
        }
        try {
            String[] parts = encryptedText.split(":", 3);
            if (parts.length != 3 || !PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported secret format.");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Secret decryption failed.", e);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Secret key derivation failed.", e);
        }
    }
}
