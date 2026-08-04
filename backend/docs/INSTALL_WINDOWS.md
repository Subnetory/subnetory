# Subnetory — Procédure d'installation complète Windows (PowerShell)

> Cette archive contient le composant `backend/`. Dans le dépôt GitHub complet,
> applique les fichiers sous `backend/`.

> Procédure source initialement validée le 21 mai 2026 ; distribution portable Sprint 2.29 validée le 15 juillet 2026 sur Windows 10/11.
> Environnement Sprint 2.29 : Temurin 21.0.11, PowerShell 7, jlink/jpackage 21.0.11, PostgreSQL 17 via Docker et Windows x64.

---

## 0. Distribution Windows portable — mode recommandé

Le Sprint 2.29 fournit une `app-image` Windows x64 autonome.
Le runtime Java 21 est inclus : la machine cible n'a pas besoin d'un JRE ou d'un JDK séparé.
PostgreSQL 17 reste externe au package.

### 0.1 Construire les artefacts

Depuis la racine du dépôt avec PowerShell 7 :

```powershell
pwsh.exe -File .\scripts\make-jpackage.ps1
```

Le script construit le JAR Spring Boot, crée un runtime Java 21 avec `jlink`, génère l'`app-image` avec `jpackage`, puis produit le ZIP portable, le manifeste et le SHA256.

Artefacts attendus :

```text
dist\windows\subnetory-0.8.5-windows-x64\
dist\windows\subnetory-0.8.5-windows-x64.zip
dist\windows\subnetory-0.8.5-windows-x64.sha256.txt
dist\windows\subnetory-0.8.5-windows-x64.manifest.txt
```

L'absence de WiX n'empêche pas la création de l'app-image et du ZIP. L'option `-Msi` exige WiX Toolset 3.

### 0.2 Vérifier l'intégrité

```powershell
$zip = ".\dist\windows\subnetory-0.8.5-windows-x64.zip"
$expected = (Get-Content ".\dist\windows\subnetory-0.8.5-windows-x64.sha256.txt" | Select-Object -First 1).Split()[0]
$actual = (Get-FileHash $zip -Algorithm SHA256).Hash
if ($actual -ne $expected) { throw "SHA256 invalide" }
```

### 0.3 Configurer PostgreSQL et les secrets

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/subnetory"
$env:SPRING_DATASOURCE_USERNAME="subnetory"
$env:SPRING_DATASOURCE_PASSWORD="<mot-de-passe-postgresql>"
$env:SUBNETORY_JWT_SECRET="<secret-aleatoire-de-32-caracteres-minimum>"
$env:SUBNETORY_ADMIN_DEFAULT_PASSWORD="<mot-de-passe-bootstrap-temporaire>"
```

Aucun secret en clair ne doit être ajouté au dépôt Git.

### 0.4 Démarrer l'application

```powershell
.\dist\windows\subnetory-0.8.5-windows-x64\Subnetory.exe
```

URL par défaut : `http://localhost:8080`.

### 0.5 Premier login obligatoire

1. se connecter avec le compte local `admin` et le mot de passe de bootstrap ;
2. Subnetory redirige vers `/profile/change-password-required` ;
3. saisir le mot de passe temporaire actuel puis un nouveau mot de passe conforme ;
4. après validation, l'accès au dashboard est restauré ;
5. l'API JWT devient utilisable avec le nouveau mot de passe.

Avant ce changement, les autres pages Web sont bloquées et `POST /api/v1/auth/token` répond HTTP 403 avec le code `PASSWORD_CHANGE_REQUIRED`. Aucun JWT n'est émis.

Les comptes LDAP ne sont pas concernés par ce mécanisme.

### 0.6 Smoke test reproductible

```powershell
pwsh.exe -ExecutionPolicy Bypass -File .\scripts\smoke-test-jpackage.ps1
```

Le test utilise un PostgreSQL 17 temporaire et valide le SHA256, l'état Flyway attendu, le changement obligatoire, le dashboard et l'émission JWT. Le conteneur et le processus sont nettoyés automatiquement.

---

## Table des matières

1. [Prérequis](#1-prérequis)
2. [Installation du projet](#2-installation-du-projet)
3. [Lancer l'application](#3-lancer-lapplication)
4. [Vérifications](#4-vérifications)
5. [Pousser sur GitHub](#5-pousser-sur-github)
6. [Arrêt](#6-arrêt)
7. [Commandes du quotidien](#7-commandes-du-quotidien)
8. [Dépannage](#8-dépannage)

---

## 1. Prérequis

### 1.1 Java 21 Temurin

**Télécharger** : https://adoptium.net/temurin/releases/?version=21

Paramètres :
- Version : **21** / OS : **Windows** / Architecture : **x64** / Package : **JDK** / Format : **.msi**

**Lancer le `.msi` en tant qu'administrateur** (clic droit → "Exécuter en tant qu'administrateur").

À l'écran "Installation personnalisée", **activer les 4 options** en cliquant sur les croix rouges :

```
✅ Ajouter au PATH
✅ Associer les .jar
✅ Définir la variable JAVA_HOME   ← cliquer sur la croix rouge
✅ Clés de registre JavaSoft       ← cliquer sur la croix rouge
```

**Vérification** — ouvrir un **nouveau** PowerShell :

```powershell
java -version
# Attendu : openjdk version "21.0.x" ... Temurin

echo $env:JAVA_HOME
# Attendu : C:\Users\...\AppData\Local\Programs\Eclipse Adoptium\jdk-21...
```

> **Si Java 8 Oracle était déjà installé** et reste prioritaire (java -version affiche 1.8) :
>
> ```powershell
> # PowerShell en ADMINISTRATEUR
> Remove-Item "C:\Program Files (x86)\Common Files\Oracle\Java\java8path" -Recurse -Force
> # Fermer PowerShell → rouvrir → vérifier java -version
> ```
>
> Cause : Oracle installe un "shim" dans le PATH système qui prend le dessus.
> La suppression de ce dossier suffit — Java 8 reste installé mais n'interfère plus.

---

### 1.2 Docker Desktop

**Télécharger** : https://www.docker.com/products/docker-desktop/

Lors de l'installation, choisir **WSL 2** (option par défaut). Redémarrer Windows si demandé.

Lancer Docker Desktop depuis le menu Démarrer. Attendre **"Engine running"** en bas de l'interface.

**Vérification** — nouveau PowerShell :

```powershell
docker --version
# Attendu : Docker version 29.x.x

docker compose version
# Attendu : Docker Compose version v5.x.x
```

> **Si `docker` n'est pas reconnu** après installation :
>
> ```powershell
> # PowerShell en ADMINISTRATEUR
> $machinePath = [System.Environment]::GetEnvironmentVariable("PATH", "Machine")
> [System.Environment]::SetEnvironmentVariable(
>     "PATH",
>     $machinePath + ";C:\Program Files\Docker\Docker\resources\bin",
>     "Machine"
> )
> # Fermer PowerShell → rouvrir → retester
> ```

---

### 1.3 Git

**Télécharger** : https://git-scm.com/download/win

Pendant l'installation :
- Écran "Initial branch name" → sélectionner **"Override"** et saisir `main`
- Tous les autres écrans → **Next** (options par défaut)

**Vérification** — nouveau PowerShell :

```powershell
git --version
# Attendu : git version 2.54.x.windows.x
```

**Configuration initiale** (une seule fois) :

```powershell
git config --global user.name "Prénom Nom"
git config --global user.email "ton@email.com"
```

---

## 2. Installation du projet

### 2.1 Extraire l'archive

```powershell
cd $HOME\Downloads
Expand-Archive -Path subnetory-sprint0.zip -DestinationPath .
cd subnetory
dir
# Attendu : pom.xml, docker-compose.yml, mvnw, mvnw.cmd, src\, docs\, ...
```

### 2.2 Télécharger le Maven Wrapper JAR

Le fichier `.mvn\wrapper\maven-wrapper.jar` (63 Ko) bootstrap Maven Wrapper.
Il n'est pas inclus dans l'archive ZIP — le télécharger une fois :

```powershell
Invoke-WebRequest `
    -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar" `
    -OutFile ".mvn\wrapper\maven-wrapper.jar"

# Vérification — doit afficher ~62 547 octets
Get-Item .mvn\wrapper\maven-wrapper.jar | Select-Object Name, Length
```

### 2.3 Démarrer PostgreSQL

```powershell
docker compose up -d
docker compose ps
# Attendu : subnetory-db   Up (healthy)
```

Le statut `healthy` peut prendre 5 à 10 secondes.

---

## 3. Lancer l'application

### 3.1 Tests unitaires

```powershell
.\mvnw.cmd test
```

> **Premier lancement** : télécharge Maven 3.9.9 (~10 Mo) + dépendances Spring Boot (~100 Mo).
> Compte 3 à 5 minutes. Les suivants prennent 10 à 15 secondes.

Attendu :
```
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 3.2 Démarrer le serveur

```powershell
.\mvnw.cmd spring-boot:run
```

Attendre ces 3 lignes dans les logs :
```
Successfully applied 9 migrations to schema "public"
Admin password initialised from configured bootstrap value. Password change is required at first login.
Tomcat started on port 8080
```

**Laisser cette fenêtre ouverte** — c'est le processus serveur.

---

## 4. Vérifications

Ouvrir un **deuxième PowerShell**.

### 4.1 Health check

```powershell
Invoke-RestMethod -Uri http://localhost:8080/actuator/health
# Attendu : status : UP
```

### 4.2 Authentification JWT

```powershell
$body = @{ username = "admin"; password = "<nouveau-mot-de-passe>" } | ConvertTo-Json
$response = Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/auth/token `
    -Method POST -ContentType "application/json" -Body $body
$response
# Attendu : accessToken + tokenType Bearer + expiresInSeconds 3600
```

### 4.3 Endpoint protégé sans token

```powershell
try {
    Invoke-RestMethod -Uri http://localhost:8080/api/v1/anything
} catch {
    Write-Host "Status: $($_.Exception.Response.StatusCode)"
}
# Attendu : Status: Unauthorized
```

### 4.4 Frontend Thymeleaf

Ouvrir **http://localhost:8080** dans le navigateur :

1. Redirection automatique vers `/login`
2. Page `◆ Subnetory` — connexion avec `admin` et le mot de passe de bootstrap configuré
3. Redirection vers `/profile/change-password-required`
4. Remplacement obligatoire du mot de passe temporaire
5. Redirection vers le dashboard après validation
6. Accès normal aux fonctions réseau et à l'API JWT

> Les liens Adresses, VLANs, Sites retournent 404 — normal au Sprint 0,
> les API REST arrivent au Sprint 1 ; les pages Web complètes seront créées au Sprint 2.

### 4.5 Vérification base de données

```powershell
# Tables créées par Flyway
docker exec subnetory-db psql -U subnetory -c "\dt"

# Compte admin
docker exec subnetory-db psql -U subnetory -c "SELECT username, auth_type, enabled FROM users;"

# Rôles de l'admin
docker exec subnetory-db psql -U subnetory -c "
    SELECT r.name FROM roles r
    JOIN user_roles ur ON r.id = ur.role_id
    JOIN users u ON u.id = ur.user_id
    WHERE u.username = 'admin';"
# Attendu : ROLE_ADMIN, ROLE_NETWORK, ROLE_IP
```

---

## 5. Pousser sur GitHub

### 5.1 Cloner le repo distant

Si le repo GitHub existe déjà avec une structure (dossier `backend/` vide) :

```powershell
cd $HOME\Downloads
git clone https://github.com/ORGANISATION/subnetory subnetory-github
cd subnetory-github
```

### 5.2 Copier le code dans backend/

```powershell
# Supprimer le placeholder si présent
Remove-Item backend\.gitkeep -ErrorAction SilentlyContinue

# Copier le contenu Sprint 0 dans backend/
Copy-Item -Path $HOME\Downloads\subnetory\* -Destination backend\ -Recurse -Force

# Retirer le .github copié par erreur (la CI reste à la racine du repo)
Remove-Item backend\.github -Recurse -Force
```

### 5.3 Vérifier, commiter et pousser

```powershell
git status
# Vérifier que les fichiers sont bien dans backend/

git add .
git commit -m "feat(backend): Subnetory Sprint 0 — squelette Spring Boot validé"
git push origin main
```

### 5.4 Après le push — versionner le wrapper JAR

Une fois le code sur GitHub, versionner le `maven-wrapper.jar` pour que les
prochains clones n'aient pas besoin de l'étape 2.2 :

```powershell
# Depuis subnetory-github/
git add backend\.mvn\wrapper\maven-wrapper.jar
git commit -m "chore: vendor maven-wrapper.jar for offline builds"
git push origin main
```

---

## 6. Arrêt

```powershell
# Dans la fenêtre du serveur : Ctrl+C

# Arrêter PostgreSQL (données conservées)
docker compose down

# Arrêter et supprimer les données (reset complet)
docker compose down -v
```

---

## 7. Commandes du quotidien

```powershell
# Depuis backend\ (ou subnetory\ selon ton dossier de travail)

# Démarrer
docker compose up -d
.\mvnw.cmd spring-boot:run

# Tests
.\mvnw.cmd test

# Compiler uniquement
.\mvnw.cmd compile

# Packager en JAR
.\mvnw.cmd clean package "-DskipTests"

# Voir les logs PostgreSQL
docker compose logs -f db

# Arrêter
docker compose down
```

---

## 8. Dépannage

### `java -version` affiche 1.8 après installation de Java 21

Java 8 Oracle installe un shim dans le PATH système qui prend le dessus.

```powershell
# Diagnostiquer
Get-Command java | Select-Object -ExpandProperty Source
# Si le résultat contient "Oracle\Java\java8path" → appliquer le fix

# Fix (PowerShell en ADMINISTRATEUR)
Remove-Item "C:\Program Files (x86)\Common Files\Oracle\Java\java8path" -Recurse -Force
# Fermer PowerShell → rouvrir → java -version doit afficher 21
```

### `docker` non reconnu dans PowerShell

Docker Desktop est installé mais pas dans le PATH. Voir section 1.2.

### `maven-wrapper.jar` absent

```
ERROR: Maven Wrapper JAR not found
Run ./bootstrap.sh, or set MVNW_REPOURL...
```

Appliquer l'étape 2.2 (`Invoke-WebRequest`).

### Erreur de compilation `SecurityConfig.java`

```
incompatible types: SubnetoryUserDetailsService cannot be converted to PasswordEncoder
```

Bug présent dans les versions antérieures de l'archive, corrigé dans la version actuelle.
`SecurityConfig.java` doit contenir :

```java
DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
provider.setUserDetailsService(userDetailsService);
```

### Port 5432 déjà utilisé

```powershell
# Option A : arrêter PostgreSQL local
Stop-Service -Name "postgresql*" -ErrorAction SilentlyContinue

# Option B : changer le port dans docker-compose.yml
# "5432:5432" → "5433:5432"
# et dans application.yml : jdbc:postgresql://localhost:5433/subnetory
```

### Port 8080 déjà utilisé

```powershell
$env:SERVER_PORT = "8090"
.\mvnw.cmd spring-boot:run
# Application disponible sur http://localhost:8090
```

### `Invoke-WebRequest` — erreur SSL/TLS

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# Relancer la commande Invoke-WebRequest
```

### `git push` demande un mot de passe

Git ouvre une fenêtre de navigateur pour l'authentification GitHub.
Si la fenêtre n'apparaît pas, configurer le gestionnaire de credentials :

```powershell
git config --global credential.helper manager
```

---

*Document validé le 21 mai 2026 — Sprint 0*
