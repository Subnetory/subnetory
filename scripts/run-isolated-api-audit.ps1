<#
.SYNOPSIS
Construit une stack Docker Compose jetable et y exécute l'audit API Subnetory.

.DESCRIPTION
La stack utilise un nom de projet, un réseau, des conteneurs et un volume
PostgreSQL distincts de l'instance locale habituelle. Les rapports restent
dans reports/ ; l'environnement jetable est supprimé à la fin, sauf avec
-KeepEnvironment.

.EXAMPLE
pwsh -NoProfile -File .\scripts\run-isolated-api-audit.ps1
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$HostPort = 18082,

    [switch]$KeepEnvironment
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backendDirectory = Join-Path $repositoryRoot 'backend'
$composeFile = Join-Path $backendDirectory 'docker-compose.yml'
$passwordFile = Join-Path $backendDirectory 'secrets\subnetory_admin_default_password'
$auditScript = Join-Path $PSScriptRoot 'audit-api.ps1'
$runKey = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputDirectory = Join-Path $repositoryRoot "reports\api-audit-isolated-$runKey"
$composeProject = "subnetory-api-audit-$PID"

if ($composeProject -notmatch '^subnetory-api-audit-[0-9]+$') {
    throw "Nom de projet Compose jetable invalide : $composeProject"
}
foreach ($requiredFile in @($composeFile, $passwordFile, $auditScript)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Fichier requis introuvable : $requiredFile"
    }
}

$previousHostPort = $env:HOST_PORT
$previousBindAddress = $env:HOST_BIND_ADDRESS
$previousSwaggerEnabled = $env:SWAGGER_ENABLED
$started = $false

try {
    $env:HOST_PORT = [string]$HostPort
    $env:HOST_BIND_ADDRESS = '127.0.0.1'
    $env:SWAGGER_ENABLED = 'true'

    Push-Location $backendDirectory
    try {
        Write-Host "Construction de la stack isolée '$composeProject' sur 127.0.0.1:$HostPort..."
        # Dès cet instant, une création partielle est possible et doit être nettoyée.
        $started = $true
        & docker compose --project-name $composeProject --file $composeFile `
            up --detach --build --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) {
            throw "Échec du démarrage Docker Compose (code $LASTEXITCODE)."
        }
        & docker compose --project-name $composeProject --file $composeFile ps
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de lire l'état de la stack isolée."
        }

        Write-Host "Audit API complet ; les restaurations et purges ciblent uniquement la base jetable..."
        # Exécution dans un processus enfant : audit-api.ps1 utilise des codes
        # de sortie ; le lanceur parent doit rester vivant pour nettoyer Compose.
        & pwsh -NoProfile -File $auditScript `
            -BaseUrl "http://127.0.0.1:$HostPort" `
            -AdminPasswordFile $passwordFile `
            -OutputDirectory $outputDirectory `
            -InitializeAdminIfRequired `
            -RequireNmapSuccess `
            -MinimumRequestIntervalMilliseconds 250 `
            -AllowDestructiveTests
        if ($LASTEXITCODE -ne 0) {
            throw "L'audit API a signalé un échec (code $LASTEXITCODE). Rapport : $outputDirectory"
        }

        Write-Host "Audit terminé avec succès. Rapport : $outputDirectory" -ForegroundColor Green
    } finally {
        Pop-Location
    }
} finally {
    if ($started -and -not $KeepEnvironment) {
        Push-Location $backendDirectory
        try {
            Write-Host "Suppression de la stack et du volume PostgreSQL strictement jetables '$composeProject'..."
            & docker compose --project-name $composeProject --file $composeFile `
                down --volumes --remove-orphans
        } finally {
            Pop-Location
        }
    } elseif ($started) {
        Write-Host "Stack conservée : $composeProject (port $HostPort)" -ForegroundColor Yellow
    }

    $env:HOST_PORT = $previousHostPort
    $env:HOST_BIND_ADDRESS = $previousBindAddress
    $env:SWAGGER_ENABLED = $previousSwaggerEnabled
}
