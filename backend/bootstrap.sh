#!/bin/sh
# Bootstrap : récupère maven-wrapper.jar si absent du dépôt.
#
# Pourquoi : le fichier .mvn/wrapper/maven-wrapper.jar (63 Ko) bootstrap
# Maven Wrapper. La pratique recommandée actuelle est de le commiter dans
# le dépôt — mais s'il est absent (par exemple parce que le ZIP de release
# a été généré dans un environnement sans accès Maven Central), ce script
# le télécharge pour toi en une commande.
#
# Usage :
#   chmod +x bootstrap.sh && ./bootstrap.sh
#
# Variable d'environnement optionnelle :
#   MVNW_REPOURL : URL d'un miroir Maven Central (ex: repo manager interne)

set -eu

WRAPPER_VERSION="3.2.0"
WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
    echo "✓ $WRAPPER_JAR déjà présent. Rien à faire."
    exit 0
fi

REPO_URL="${MVNW_REPOURL:-https://repo.maven.apache.org/maven2}"
URL="${REPO_URL}/org/apache/maven/wrapper/maven-wrapper/${WRAPPER_VERSION}/maven-wrapper-${WRAPPER_VERSION}.jar"

echo "Téléchargement : $URL"

mkdir -p .mvn/wrapper

if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$URL"
else
    echo "Ni curl ni wget disponibles."
    echo "Tentative de fallback Java pur..."
    if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
        javac .mvn/wrapper/MavenWrapperDownloader.java
        (cd .mvn/wrapper && java MavenWrapperDownloader "$URL" "../../$WRAPPER_JAR")
    else
        echo "ERREUR : ni curl, ni wget, ni javac disponibles."
        echo "Installe l'un d'eux puis relance ./bootstrap.sh"
        exit 1
    fi
fi

if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
    echo "ERREUR : téléchargement échoué."
    exit 1
fi

echo "✓ $WRAPPER_JAR récupéré ($(wc -c < "$WRAPPER_JAR") octets)."
echo "Tu peux maintenant lancer ./mvnw test"
