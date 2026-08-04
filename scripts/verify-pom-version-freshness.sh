#!/bin/bash
# Detecte le cas ou main a avance apres un tag de release sans que
# backend/pom.xml ait ete rebascule vers la prochaine version -SNAPSHOT
# (audit 04/08/2026, correctif FAIBLE).
#
# Contexte : verify-release-consistency.sh verifie deja, au moment de
# TAGUER une release, que pom.xml est exactement a la version du tag et
# n'est pas en -SNAPSHOT. Mais rien ne verifiait qu'apres ce tag, le tout
# premier commit suivant rebascule vers la prochaine -SNAPSHOT — a plusieurs
# reprises cette session (v0.8.1, v0.8.2, v0.8.3), ce bump a ete oublie :
# main continuait a porter la version exacte du tag deja publie, alors que
# son contenu avait deja diverge. Un JAR construit depuis main a ce moment
# annonce donc la meme version qu'une release deja publiee, avec un contenu
# different — ambiguite de tracabilite.
#
# Principe : si pom.xml porte une version non-SNAPSHOT qui correspond a un
# tag EXISTANT ne pointant pas vers HEAD, c'est que main a avance depuis ce
# tag sans bump. Si le tag n'existe pas encore (fenetre de preparation de
# release, juste avant de taguer), ou si HEAD est exactement le commit
# tague, ce n'est pas une erreur.
#
# Necessite l'historique complet des tags (checkout avec fetch-depth: 0 ou
# fetch-tags: true), sinon les tags distants ne sont pas visibles ici.
#
# Usage : scripts/verify-pom-version-freshness.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

POM_VERSION="$(grep -m1 -oP '(?<=^    <version>)[^<]+' backend/pom.xml || true)"
if [[ -z "$POM_VERSION" ]]; then
    echo "REFUS : impossible de lire la version de backend/pom.xml." >&2
    exit 1
fi

if [[ "$POM_VERSION" == *-SNAPSHOT ]]; then
    echo "OK : backend/pom.xml est en '$POM_VERSION' (developpement en cours)."
    exit 0
fi

TAG="v$POM_VERSION"

if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "OK : backend/pom.xml est en '$POM_VERSION', aucun tag '$TAG' n'existe encore (fenetre de preparation de release)."
    exit 0
fi

TAG_COMMIT="$(git rev-parse "refs/tags/$TAG")"
HEAD_COMMIT="$(git rev-parse HEAD)"

if [[ "$TAG_COMMIT" == "$HEAD_COMMIT" ]]; then
    echo "OK : backend/pom.xml est en '$POM_VERSION', HEAD est exactement le commit tague $TAG."
    exit 0
fi

echo "REFUS : backend/pom.xml est encore en '$POM_VERSION', qui correspond au tag '$TAG' deja publie (commit $TAG_COMMIT)." >&2
echo "        HEAD ($HEAD_COMMIT) est different : main a avance depuis cette release sans rebasculer la version." >&2
echo "        Un JAR construit maintenant annoncerait la version '$POM_VERSION' avec un contenu different de la release deja publiee." >&2
echo "        Corriger : bump backend/pom.xml vers la prochaine version -SNAPSHOT (ex. si le tag est $TAG, passer a la version mineure ou patch suivante suffixee -SNAPSHOT)." >&2
exit 1
