# restore-postgres.ps1
# Subnetory - Restauration PostgreSQL via Docker
#
# Emplacement : backend/scripts/restore-postgres.ps1
#
# Usage :
#   .\restore-postgres.ps1 -BackupFile ..\..\backups\daily\subnetory_2026-06-03.sql.gz
#   .\restore-postgres.ps1 -BackupFile ..\..\backups\daily\subnetory_2026-06-03.sql.gz -Force
#   .\restore-postgres.ps1 -BackupFile ..\..\backups\daily\subnetory_2026-06-03.sql.gz -BackupBeforeRestore
#
# ATTENTION : la restauration ecrase toutes les donnees existantes dans la base.
#
# Decompression : GZipStream .NET — aucun outil externe requis.
# psql utilise ON_ERROR_STOP=on : la restauration s'arrete a la premiere erreur SQL.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,

    [string]$Container = "backend-db-1",

    [string]$DbName = "subnetory",

    [string]$DbUser = "subnetory",

    [string]$EnvFile = "",

    [switch]$BackupBeforeRestore,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ─── Fonctions utilitaires ────────────────────────────────────────────────────

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $color = switch ($Level) {
        "OK"    { "Green" }
        "WARN"  { "Yellow" }
        "ERROR" { "Red" }
        default { "Cyan" }
    }
    Write-Host "[$ts] [$Level] $Message" -ForegroundColor $color
}

function Find-EnvFile {
    # Le script est dans backend/scripts/.
    # On cherche backend/.env via $PSScriptRoot\..\.env
    $candidates = @(
        (Join-Path $PSScriptRoot "..\.env"),              # backend/.env (cas nominal)
        (Join-Path (Get-Location) "backend\.env"),        # depuis la racine du projet
        (Join-Path (Get-Location) ".env")                 # depuis backend/ directement
    )
    foreach ($c in $candidates) {
        $resolved = $null
        try { $resolved = (Resolve-Path $c -ErrorAction SilentlyContinue)?.Path } catch {}
        if ($resolved -and (Test-Path $resolved)) {
            return $resolved
        }
    }
    return $null
}

function Read-EnvPassword {
    param([string]$EnvFilePath)
    if (-not (Test-Path $EnvFilePath)) {
        return $null
    }
    $lines = Get-Content $EnvFilePath -Encoding UTF8
    foreach ($line in $lines) {
        if ($line -match '^\s*POSTGRES_PASSWORD\s*=\s*(.+)$') {
            $val = $Matches[1].Trim()
            $val = $val -replace '^[''"]|[''"]$', ''
            return $val
        }
    }
    return $null
}

function Assert-ContainerRunning {
    param([string]$ContainerName)
    $status = docker inspect --format "{{.State.Status}}" $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne "running") {
        throw "Le conteneur '$ContainerName' n'est pas en cours d'execution. Verifier 'docker compose ps'."
    }
}

function Expand-GzipToTempFile {
    param([string]$GzipPath)
    $tmpPath = Join-Path ([System.IO.Path]::GetTempPath()) "$([System.IO.Path]::GetRandomFileName()).sql"
    $fs  = [System.IO.FileStream]::new($GzipPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read)
    $gz  = [System.IO.Compression.GZipStream]::new($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $out = [System.IO.FileStream]::new($tmpPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        $gz.CopyTo($out)
    } finally {
        $out.Close()
        $gz.Close()
        $fs.Close()
    }
    return $tmpPath
}

# ─── Debut du script ──────────────────────────────────────────────────────────

Write-Log "=== Subnetory - Restauration PostgreSQL ==="
Write-Host ""

# Avertissement
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Red
Write-Host "║  ATTENTION : OPERATION DESTRUCTIVE                          ║" -ForegroundColor Red
Write-Host "║                                                              ║" -ForegroundColor Red
Write-Host "║  Cette restauration va ecraser TOUTES les donnees           ║" -ForegroundColor Red
Write-Host "║  de la base. Cette action est IRREVERSIBLE                  ║" -ForegroundColor Red
Write-Host "║  sans une autre sauvegarde.                                 ║" -ForegroundColor Red
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Red
Write-Host ""

# Verifier le fichier de sauvegarde
$resolvedBackup = $null
try { $resolvedBackup = (Resolve-Path $BackupFile -ErrorAction SilentlyContinue)?.Path } catch {}
if ($null -eq $resolvedBackup -or -not (Test-Path $resolvedBackup)) {
    Write-Log "Fichier de sauvegarde introuvable : $BackupFile" "ERROR"
    exit 1
}
$BackupFile = $resolvedBackup
$fileSize   = (Get-Item $BackupFile).Length
$fileSizeKb = [math]::Round($fileSize / 1KB, 1)
Write-Log "Fichier de sauvegarde : $BackupFile ($fileSizeKb Ko)"

# Resoudre le fichier .env
if ($EnvFile -eq "") {
    $EnvFile = Find-EnvFile
    if ($null -eq $EnvFile) {
        Write-Log "Impossible de localiser le fichier .env. Utiliser -EnvFile pour le specifier." "ERROR"
        exit 1
    }
}
Write-Log "Fichier .env : $EnvFile"

# Lire le mot de passe
$pgPassword = Read-EnvPassword -EnvFilePath $EnvFile
if ($null -eq $pgPassword -or $pgPassword -eq "") {
    Write-Log "POSTGRES_PASSWORD introuvable dans $EnvFile" "ERROR"
    exit 1
}

# Verifier que Docker est disponible
try {
    docker info *>$null
    if ($LASTEXITCODE -ne 0) { throw "code de retour non nul" }
} catch {
    Write-Log "Docker n'est pas accessible. Verifier que Docker Desktop est demarre." "ERROR"
    exit 1
}

# Verifier que le conteneur est actif
try {
    Assert-ContainerRunning -ContainerName $Container
} catch {
    Write-Log $_.Exception.Message "ERROR"
    exit 1
}
Write-Log "Conteneur '$Container' actif"

# Confirmation interactive (sauf si -Force)
if (-not $Force) {
    Write-Host ""
    $confirm = Read-Host "Confirmer la restauration depuis '$([System.IO.Path]::GetFileName($BackupFile))' ? Taper OUI pour continuer"
    if ($confirm -ne "OUI") {
        Write-Log "Restauration annulee par l'utilisateur." "WARN"
        exit 0
    }
}

# Sauvegarde de securite avant restauration
if ($BackupBeforeRestore) {
    Write-Log "Creation d'une sauvegarde de securite avant restauration..."
    $backupScript = Join-Path $PSScriptRoot "backup-postgres.ps1"
    if (-not (Test-Path $backupScript)) {
        Write-Log "backup-postgres.ps1 introuvable dans $PSScriptRoot. Sauvegarde ignoree." "WARN"
    } else {
        try {
            & $backupScript -Type "daily" -EnvFile $EnvFile -Container $Container -DbName $DbName -DbUser $DbUser
            Write-Log "Sauvegarde de securite creee." "OK"
        } catch {
            Write-Log "Echec de la sauvegarde de securite : $($_.Exception.Message)" "WARN"
            Write-Log "La restauration va quand meme continuer." "WARN"
        }
    }
}

# Decompression via GZipStream .NET
Write-Log "Decompression du fichier .sql.gz (.NET natif)..."
$tmpSql = $null
try {
    $tmpSql = Expand-GzipToTempFile -GzipPath $BackupFile
    $sqlSize = (Get-Item $tmpSql).Length
    Write-Log "SQL decompresse : $([math]::Round($sqlSize / 1KB, 1)) Ko"
} catch {
    Write-Log "Echec de la decompression : $($_.Exception.Message)" "ERROR"
    if ($tmpSql -and (Test-Path $tmpSql)) { Remove-Item $tmpSql -Force }
    exit 1
}

# Restauration via psql avec ON_ERROR_STOP=on
Write-Log "Demarrage de la restauration via psql..."

try {
    $psqlArgs = @(
        "exec", "-i",
        "-e", "PGPASSWORD=$pgPassword",
        $Container,
        "psql",
        "--username=$DbUser",
        "--dbname=$DbName",
        "--quiet",
        "--no-password",
        "--set=ON_ERROR_STOP=on"
    )

    # Pipe le contenu SQL decompresse vers psql dans le conteneur
    Get-Content -Path $tmpSql -Encoding UTF8 -Raw | & docker @psqlArgs

    if ($LASTEXITCODE -ne 0) {
        throw "psql a retourne une erreur (code $LASTEXITCODE). Verifier les logs ci-dessus."
    }
} catch {
    Write-Log "Echec de la restauration : $($_.Exception.Message)" "ERROR"
    exit 1
} finally {
    if ($tmpSql -and (Test-Path $tmpSql)) { Remove-Item $tmpSql -Force }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Log "=== Restauration terminee avec succes ===" "OK"
Write-Log "Base '$DbName' restauree depuis : $([System.IO.Path]::GetFileName($BackupFile))" "OK"
Write-Host ""
Write-Log "Etape suivante recommandee : redemarrer le conteneur app."
Write-Log "  docker compose restart app"
