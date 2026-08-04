# check-context-integrity.ps1
# Subnetory - Diagnostic (et correction optionnelle) des incoherences
# context_id/site_id historiques entre Subnet/Address et leur parent.
#
# Emplacement : backend/scripts/check-context-integrity.ps1
#
# Contexte : avant les correctifs v0.8.1 (deplacement Site/VLAN) et v0.8.2
# (deplacement Subnet), rien n'empechait de changer le contexte d'un Site,
# le site d'un VLAN, ou le contexte/site/CIDR d'un Subnet alors que des
# enfants existaient encore en dessous. Ce script est un diagnostic pour une
# instance mise a jour depuis une version anterieure a ces correctifs ; sur
# une instance qui n'a jamais transite par ces bugs, il ne doit rien trouver.
# Voir backend/scripts/check-context-integrity.sql / fix-context-integrity.sql
# pour le detail des requetes.
#
# Usage :
#   .\check-context-integrity.ps1                 # rapport seul (lecture)
#   .\check-context-integrity.ps1 -Fix             # corrige, demande confirmation
#   .\check-context-integrity.ps1 -Fix -Force      # corrige sans confirmation
#
# Prerequis :
#   - Docker Desktop installe et demarre
#   - Le conteneur db doit etre actif (backend-db-1 par defaut)
#
# Mot de passe PostgreSQL (audit post-release 04/08/2026, correctif FAIBLE) :
# lu DEPUIS L'INTERIEUR du conteneur, directement sur /run/secrets/postgres_password
# (deja monte par Docker Compose pour le service "db" — voir backend/docker-compose.yml).
# Jamais transmis en argument de "docker exec" : un argument de ligne de
# commande est visible par tout autre processus local via "ps"/"docker top"
# le temps de l'execution, contrairement a une valeur lue par le conteneur
# lui-meme sur un fichier qu'il a deja le droit de lire.
#
# Ne corrige (avec -Fix) que les categories sans ambiguite (context_id/site_id
# d'un Subnet realigne sur son Site/VLAN, d'une Address realignee sur son
# Subnet) — exactement ce que fait deja le code applicatif a chaque
# ecriture. Les incoherences entre un Subnet et son parent restent affichees
# mais jamais corrigees automatiquement : l'arbitrage (corriger l'enfant ou
# reconsiderer le lien parent) reste manuel.

[CmdletBinding()]
param(
    [switch]$Fix,
    [switch]$Force,
    [string]$Container = "backend-db-1",
    [string]$DbName = "subnetory",
    [string]$DbUser = "subnetory"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $ts = (Get-Date).ToString("s")
    Write-Host "[$ts] [$Level] $Message"
}

function Assert-ContainerRunning {
    param([string]$ContainerName)
    $status = docker inspect --format "{{.State.Status}}" $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne "running") {
        throw "Le conteneur '$ContainerName' n'est pas en cours d'execution. Verifier 'docker compose ps'."
    }
}

Write-Log "=== Subnetory - Diagnostic d'integrite context_id/site_id ==="

try {
    docker info *>$null
    if ($LASTEXITCODE -ne 0) { throw "code de retour non nul" }
} catch {
    Write-Log "Docker n'est pas accessible. Verifier que Docker Desktop est demarre." "ERROR"
    exit 1
}

try {
    Assert-ContainerRunning -ContainerName $Container
} catch {
    Write-Log $_.Exception.Message "ERROR"
    exit 1
}
Write-Log "Conteneur '$Container' actif"

$sqlDir = $PSScriptRoot
$checkSql = Join-Path $sqlDir "check-context-integrity.sql"
$fixSql = Join-Path $sqlDir "fix-context-integrity.sql"

function Invoke-PsqlFile {
    param([string]$SqlPath)
    # Le mot de passe est lu par le shell DANS le conteneur (fichier de
    # secret deja monte par Compose), jamais passe en argument de "docker
    # exec" cote hote — voir le commentaire d'en-tete du fichier.
    $shellCommand = 'PGPASSWORD="$(cat /run/secrets/postgres_password)" psql --username={0} --dbname={1} -v ON_ERROR_STOP=1 -f -' -f $DbUser, $DbName
    $psqlArgs = @(
        "exec", "-i",
        $Container,
        "sh", "-c",
        $shellCommand
    )
    Get-Content $SqlPath -Raw -Encoding UTF8 | & docker @psqlArgs
    return $LASTEXITCODE
}

try {
    Write-Log "Execution du diagnostic ($checkSql)..."
    $exitCode = Invoke-PsqlFile -SqlPath $checkSql
    if ($exitCode -ne 0) {
        throw "Le diagnostic a retourne une erreur (code $exitCode)"
    }

    if ($Fix) {
        if (-not $Force) {
            Write-Host ""
            $confirm = Read-Host "Appliquer les corrections (categories A, B, E, F ci-dessus) ? Taper OUI pour continuer"
            if ($confirm -ne "OUI") {
                Write-Log "Correction annulee par l'utilisateur." "WARN"
                exit 0
            }
        }
        Write-Log "Recommande : effectuer une sauvegarde avant correction (backup-postgres.ps1)." "WARN"
        Write-Log "Execution de la correction ($fixSql)..."
        $exitCode = Invoke-PsqlFile -SqlPath $fixSql
        if ($exitCode -ne 0) {
            throw "La correction a retourne une erreur (code $exitCode) ; transaction annulee (ROLLBACK implicite)."
        }

        Write-Log "Re-execution du diagnostic pour confirmation..."
        $exitCode = Invoke-PsqlFile -SqlPath $checkSql
        if ($exitCode -ne 0) {
            throw "Le diagnostic post-correction a retourne une erreur (code $exitCode)"
        }
    }
} catch {
    Write-Log "Echec : $($_.Exception.Message)" "ERROR"
    exit 1
}

Write-Log "Termine."
