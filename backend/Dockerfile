# ── Stage 1 : Build ────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build

# Copier le wrapper Maven et le pom.xml en premier.
# Tant que pom.xml ne change pas, Docker réutilise le cache des dépendances.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Le dépôt conserve actuellement mvnw en CRLF sur Windows.
# Normaliser uniquement la copie présente dans l'image évite le shebang /bin/sh\r.
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw dependency:go-offline -q

COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ── Stage 2 : Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Nmap est requis par la fonctionnalité de découverte réseau.
# postgresql17-client fournit pg_dump/pg_restore, requis par la gestion des
# sauvegardes (Phase 7 audit, 31/07/2026 — dev.subnetory.backup.
# BackupExecutionService) : l'application execute ces outils elle-meme via
# ProcessBuilder, identiquement en Docker Compose et en Kubernetes.
# Version 17 alignee sur l'image serveur postgres:17-alpine (docker-compose.yml,
# postgresql-statefulset.yaml du chart Helm) — pg_dump/pg_restore doivent
# rester du meme major que le serveur pour eviter les incompatibilites de
# format de dump. A mettre a jour si la version du serveur Postgres change.
# Le paquet est installé par root pendant le build, puis l'application, Nmap
# et pg_dump/pg_restore s'exécutent avec l'utilisateur non-root subnetory,
# sans privilèges ajoutés.
#
# apk upgrade --no-cache : applique les correctifs de sécurité disponibles sur
# les paquets systeme deja presents dans l'image de base (detecte par le job
# CI dependency-scan / Trivy, ex. libexpat CVE-2026-56131/CVE-2026-56408,
# p11-kit CVE-2026-2100). L'image de base elle-meme n'est pas toujours a jour
# au moment du build ; cette etape garantit les derniers correctifs Alpine
# disponibles au moment du build de l'image applicative.
RUN apk upgrade --no-cache \
    && apk add --no-cache nmap tzdata postgresql17-client \
    && addgroup -S subnetory \
    && adduser -S subnetory -G subnetory \
    && mkdir -p /var/subnetory/backups \
    && chown -R subnetory:subnetory /var/subnetory

# Fuseau horaire de l'application. Sans ceci, l'image Alpine/JRE demarre en
# UTC par defaut : les horodatages (last_seen_at, scannedAt, etc., stockes en
# TIMESTAMPTZ mais generes via OffsetDateTime.now()) sont alors calcules avec
# l'offset UTC au lieu de l'heure locale, d'ou un decalage visible a l'affichage
# (ex: 2h en ete, 1h en hiver, pour un deploiement en France).
ENV TZ=Europe/Paris
USER subnetory

COPY --from=builder /build/target/subnetory-*.jar app.jar

EXPOSE 8080

# Le healthcheck utilise SERVER_PORT pour rester cohérent si le port est modifié.
# start_period laisse le temps à Flyway d'appliquer les migrations au premier démarrage.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- "http://localhost:${SERVER_PORT:-8080}/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
