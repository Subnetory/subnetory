#!/bin/bash
# Verifie qu'un tag de release "vX.Y.Z" est coherent avec le reste du depot
# avant de construire/publier quoi que ce soit (audit 03/08/2026).
#
# Contexte : la release v0.8.1 a ete publiee alors que backend/pom.xml
# portait encore "0.8.1-SNAPSHOT" (JAR publie nomme
# subnetory-0.8.1-SNAPSHOT.jar), et le chart Helm pointait toujours vers
# l'image v0.8.0 par defaut (un "helm install" sans surcharge n'installait
# donc pas les correctifs de v0.8.1). Erreur de process, pas de bug de
# code : rien ne verifiait automatiquement ces alignements avant de tagger.
# Ce script existe pour que ca ne puisse plus se reproduire silencieusement.
#
# Usage :
#   scripts/verify-release-consistency.sh v0.8.2
#   (ou, en CI sur un push de tag : scripts/verify-release-consistency.sh "$GITHUB_REF_NAME")
#
# Sortie : 0 si tout est coherent, 1 et un message explicite sinon.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="${1:-${GITHUB_REF_NAME:-}}"
if [[ -z "$TAG" ]]; then
    echo "Usage: $0 vX.Y.Z" >&2
    exit 1
fi
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "REFUS : tag '$TAG' ne suit pas le format vX.Y.Z attendu par release.yml." >&2
    exit 1
fi
VERSION="${TAG#v}"

errors=0
fail() {
    echo "REFUS : $1" >&2
    errors=$((errors + 1))
}

# --- 1. pom.xml : version exacte, jamais -SNAPSHOT --------------------
# Le premier <version> du fichier est celui du parent Spring Boot (indente
# de 8 espaces, dans <parent>) : on cible specifiquement le <version> du
# projet lui-meme, indente de 4 espaces au niveau racine.
POM_VERSION="$(grep -m1 -oP '(?<=^    <version>)[^<]+' backend/pom.xml || true)"
if [[ "$POM_VERSION" == *-SNAPSHOT ]]; then
    fail "backend/pom.xml est encore en '$POM_VERSION' (SNAPSHOT). Retirer le suffixe -SNAPSHOT avant de tagger $TAG."
elif [[ "$POM_VERSION" != "$VERSION" ]]; then
    fail "backend/pom.xml est en '$POM_VERSION', attendu '$VERSION' pour le tag $TAG."
fi

# --- 2. Chart Helm : appVersion et image.tag par defaut ----------------
CHART_APP_VERSION="$(grep -m1 -oP '(?<=^appVersion: ")[^"]+' charts/subnetory/Chart.yaml || true)"
if [[ "$CHART_APP_VERSION" != "$VERSION" ]]; then
    fail "charts/subnetory/Chart.yaml: appVersion='$CHART_APP_VERSION', attendu '$VERSION'."
fi

CHART_IMAGE_TAG="$(grep -m1 -oP '(?<=^  tag: ")[^"]+' charts/subnetory/values.yaml || true)"
if [[ "$CHART_IMAGE_TAG" != "$TAG" ]]; then
    fail "charts/subnetory/values.yaml: image.tag='$CHART_IMAGE_TAG', attendu '$TAG' (un 'helm install' sans surcharge doit installer l'image que ce tag publie)."
fi

# --- 3. Docs affichant la version courante ------------------------------
if ! grep -qF "Current released version: \`$TAG\`" README.md; then
    fail "README.md ne mentionne pas 'Current released version: \`$TAG\`'."
fi

if ! grep -qF "Latest tagged release (currently \`$TAG\`)" SECURITY.md; then
    fail "SECURITY.md ne mentionne pas 'Latest tagged release (currently \`$TAG\`)'."
fi

# --- 4. mvnw doit rester executable dans l'arbre git --------------------
MVNW_MODE="$(git ls-files -s backend/mvnw | awk '{print $1}')"
if [[ "$MVNW_MODE" != "100755" ]]; then
    fail "backend/mvnw n'est pas executable dans l'index git (mode '$MVNW_MODE', attendu 100755). 'git update-index --chmod=+x backend/mvnw'."
fi

# --- 5. dependabot.yml ne doit jamais exister sur ce depot (public) -----
# Defense en profondeur : le script de sync public->prive exclut deja ce
# fichier de l'arbre publie, mais un oubli passe deja produit ce cas trois
# fois de suite dans cette session. Ceinture et bretelles.
if [[ -f .github/dependabot.yml ]]; then
    fail ".github/dependabot.yml est present sur ce depot public — reintroduit Dependabot (deja desactive deliberement, voir CONTRIBUTING.md)."
fi

# --- 6. CHANGELOG.md doit avoir une section pour ce tag ------------------
if ! grep -qF "## [$VERSION] - " CHANGELOG.md; then
    fail "CHANGELOG.md n'a pas de section '## [$VERSION] - AAAA-MM-JJ'."
fi

if [[ "$errors" -gt 0 ]]; then
    echo "" >&2
    echo "$errors incoherence(s) detectee(s) avant de publier $TAG. Rien n'a ete construit ni publie." >&2
    exit 1
fi

echo "OK : le depot est coherent avec le tag $TAG."
