package dev.subnetory.dto;

public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds
) {
    public static TokenResponse of(String token, long expirationMinutes) {
        return new TokenResponse(token, "Bearer", expirationMinutes * 60);
    }
}
