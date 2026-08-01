package dev.subnetory.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration OpenAPI / Swagger UI — Sprint 2.8.
 *
 * <p>Déclare le schéma d'authentification Bearer JWT utilisé par tous les
 * endpoints /api/v1/**. La sécurité est appliquée globalement via
 * {@code @SecurityRequirement(name = "bearerAuth")}.</p>
 *
 * <p>Pour obtenir un token : {@code POST /api/v1/auth/token}
 * avec {@code {"username":"...","password":"..."}}.</p>
 *
 * <p>URLs d'accès :</p>
 * <ul>
 *   <li>Swagger UI : <a href="/swagger-ui.html">/swagger-ui.html</a></li>
 *   <li>Spec JSON  : <a href="/v3/api-docs">/v3/api-docs</a></li>
 *   <li>Spec YAML  : <a href="/v3/api-docs.yaml">/v3/api-docs.yaml</a></li>
 * </ul>
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title       = "Subnetory API",
        version     = "v1",
        description = "API REST de Subnetory — IPAM moderne. " +
                      "Authentification via JWT Bearer (POST /api/v1/auth/token)."
    ),
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name        = "bearerAuth",
    type        = SecuritySchemeType.HTTP,
    scheme      = "bearer",
    bearerFormat = "JWT",
    description = "Token JWT obtenu via POST /api/v1/auth/token. " +
                  "Format : Authorization: Bearer <token>"
)
public class OpenApiConfig {
    // Pas de beans supplémentaires nécessaires :
    // springdoc auto-découvre tous les @RestController et génère la spec.
}
