# Subnetory — Prérequis avant premier build

## Maven Wrapper JAR

Le fichier `backend/.mvn/wrapper/maven-wrapper.jar` doit être versionné dans le dépôt
afin que le projet puisse être construit après un `git clone` sans étape manuelle.

Si le fichier est absent dans une archive temporaire, le télécharger une fois :

### Windows (PowerShell)

```powershell
cd backend
Invoke-WebRequest `
    -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar" `
    -OutFile ".mvn\wrapper\maven-wrapper.jar"
```

### Linux/macOS

```bash
curl -sL https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar \
     -o backend/.mvn/wrapper/maven-wrapper.jar
```

### Après téléchargement — versionner une fois

```bash
git add backend/.mvn/wrapper/maven-wrapper.jar
git commit -m "chore: vendor maven-wrapper.jar for offline builds"
```

## HTMX

Sprint 2.1 n'utilise pas HTMX (retiré pour simplifier la gestion du CSRF).
Le bouton scan utilise un formulaire POST standard avec PRG (Post/Redirect/Get).

## Nmap (pour le scan réseau)

Le scan réseau (`POST /network/subnets/{id}/scan`) requiert Nmap installé sur
le serveur Subnetory. Subnetory n'embarque pas de binaire.

```bash
# Debian/Ubuntu
apt-get install -y nmap

# RHEL/CentOS
yum install -y nmap
```

Configurer si chemin non standard :
```yaml
subnetory:
  scan:
    nmap-path: /usr/bin/nmap
```
