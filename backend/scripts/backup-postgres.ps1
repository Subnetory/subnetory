# backup-postgres.ps1
# Subnetory - Sauvegarde PostgreSQL via Docker
#
# Emplacement : backend/scripts/backup-postgres.ps1
#
# Usage :
#   .\backup-postgres.ps1
#   .\backup-postgres.ps1 -Type daily -Retention 31
#   .\backup-postgres.ps1 -Type hourly -BackupDir C:\subnetory\backups
#
# Prerequis :
#   - Docker Desktop installe et demarre
#   - Le conteneur db doit etre actif (backend-db-1 par defaut)
#   - Le fichier backend/.env doit exister et contenir POSTGRES_PASSWORD
#
# Compression : GZipStream .NET — aucun outil externe requis (pas de gzip.exe).
# Compatible Windows + Docker Desktop sans Git Bash.

[CmdletBinding()]
param(
    [ValidateSet("hourly", "daily", "monthly", "quarterly")]
    [string]$Type = "daily",

    [string]$BackupDir = "",

    [int]$Retention = -1,

    [string]$Container = "backend-db-1",

    [string]$DbName = "subnetory",

    [string]$DbUser = "subnetory",

    [string]$EnvFile = ""
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
    # Puis quelques chemins alternatifs pour les executions depuis d'autres dossiers.
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
            # Supprimer les guillemets eventuels autour de la valeur
            $val = $val -replace '^[''"]|[''"]$', ''
            return $val
        }
    }
    return $null
}

function Get-RetentionDefault {
    param([string]$BackupType)
    switch ($BackupType) {
        "hourly"    { return 168 }
        "daily"     { return 31 }
        "monthly"   { return 12 }
        "quarterly" { return 4  }
        default     { return 30 }
    }
}

function Get-BackupFilename {
    param([string]$BackupType, [string]$Database)
    $now = Get-Date
    $suffix = switch ($BackupType) {
        "hourly"    { $now.ToString("yyyy-MM-dd_HH-00") }
        "daily"     { $now.ToString("yyyy-MM-dd") }
        "monthly"   { $now.ToString("yyyy-MM") }
        "quarterly" {
            $q = [math]::Ceiling($now.Month / 3)
            "$($now.Year)-Q$q"
        }
        default     { $now.ToString("yyyy-MM-dd") }
    }
    return "${Database}_${suffix}.sql.gz"
}

function Assert-ContainerRunning {
    param([string]$ContainerName)
    $status = docker inspect --format "{{.State.Status}}" $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne "running") {
        throw "Le conteneur '$ContainerName' n'est pas en cours d'execution. Verifier 'docker compose ps'."
    }
}

function Remove-OldBackups {
    param([string]$Dir, [int]$KeepCount)
    $files = @(Get-ChildItem -Path $Dir -Filter "*.sql.gz" |
             Sort-Object LastWriteTime -Descending)
    if ($files.Count -le $KeepCount) { return }
    $toDelete = $files | Select-Object -Skip $KeepCount
    foreach ($f in $toDelete) {
        Remove-Item $f.FullName -Force
        Write-Log "Supprime (rotation) : $($f.Name)" "WARN"
    }
}

function Compress-BytesToGzip {
    param([byte[]]$Bytes, [string]$OutputPath)
    $fs  = [System.IO.File]::Create($OutputPath)
    $gz  = [System.IO.Compression.GZipStream]::new($fs, [System.IO.Compression.CompressionMode]::Compress)
    try {
        $gz.Write($Bytes, 0, $Bytes.Length)
    } finally {
        $gz.Close()
        $fs.Close()
    }
}

# ─── Debut du script ──────────────────────────────────────────────────────────

Write-Log "=== Subnetory - Sauvegarde PostgreSQL ($Type) ==="

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
Write-Log "Mot de passe PostgreSQL lu depuis .env"

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

# Resoudre le dossier de sauvegarde
# Par defaut : backups/ a la racine du projet (backend/scripts/../../backups)
if ($BackupDir -eq "") {
    $BackupDir = Join-Path $PSScriptRoot "..\..\backups"
}
$BackupDir  = [System.IO.Path]::GetFullPath($BackupDir)
$typeDir    = Join-Path $BackupDir $Type
if (-not (Test-Path $typeDir)) {
    New-Item -ItemType Directory -Path $typeDir -Force | Out-Null
    Write-Log "Dossier cree : $typeDir"
}

# Nom du fichier de sauvegarde
$filename = Get-BackupFilename -BackupType $Type -Database $DbName
$destPath = Join-Path $typeDir $filename

# Fichier SQL temporaire (avant compression)
$tmpSql = Join-Path ([System.IO.Path]::GetTempPath()) "$([System.IO.Path]::GetRandomFileName()).sql"

Write-Log "Demarrage du pg_dump vers fichier temporaire..."

try {
    # Executer pg_dump dans le conteneur et capturer la sortie SQL brute
    $dumpArgs = @(
        "exec", "-i",
        "-e", "PGPASSWORD=$pgPassword",
        $Container,
        "pg_dump",
        "--username=$DbUser",
        "--dbname=$DbName",
        "--clean",
        "--if-exists",
        "--no-owner",
        "--no-acl"
    )

    # Capturer stdout en bytes via Out-File pour eviter les problemes d'encodage
    & docker @dumpArgs | Set-Content -Path $tmpSql -Encoding UTF8

    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump a retourne une erreur (code $LASTEXITCODE)"
    }

    # Verifier que le fichier SQL n'est pas vide
    $sqlSize = (Get-Item $tmpSql).Length
    if ($sqlSize -eq 0) {
        throw "Le dump SQL est vide. Verifier les permissions et le nom de la base."
    }
    Write-Log "Dump SQL brut : $([math]::Round($sqlSize / 1KB, 1)) Ko"

    # Comprimer via GZipStream .NET (aucun outil externe)
    Write-Log "Compression GZip (.NET natif)..."
    $sqlBytes = [System.IO.File]::ReadAllBytes($tmpSql)
    Compress-BytesToGzip -Bytes $sqlBytes -OutputPath $destPath

} catch {
    Write-Log "Echec du backup : $($_.Exception.Message)" "ERROR"
    if (Test-Path $destPath) { Remove-Item $destPath -Force }
    exit 1
} finally {
    # Supprimer le fichier temporaire dans tous les cas
    if (Test-Path $tmpSql) { Remove-Item $tmpSql -Force }
    # Effacer la variable de mot de passe de l'environnement
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

# Verifier le fichier produit
if (-not (Test-Path $destPath)) {
    Write-Log "Le fichier de sauvegarde n'a pas ete cree : $destPath" "ERROR"
    exit 1
}
$gzSize = (Get-Item $destPath).Length
if ($gzSize -eq 0) {
    Write-Log "Le fichier de sauvegarde est vide : $destPath" "ERROR"
    Remove-Item $destPath -Force
    exit 1
}
$gzSizeKb = [math]::Round($gzSize / 1KB, 1)
Write-Log "Sauvegarde reussie : $filename ($gzSizeKb Ko)" "OK"

# Rotation des anciennes sauvegardes
$keepCount = if ($Retention -gt 0) { $Retention } else { Get-RetentionDefault -BackupType $Type }
Write-Log "Rotation : conservation des $keepCount derniers fichiers dans $typeDir"
Remove-OldBackups -Dir $typeDir -KeepCount $keepCount

Write-Log "=== Sauvegarde terminee ===" "OK"
Write-Log "Fichier : $destPath"

