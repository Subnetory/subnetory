#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [string]$Namespace = "subnetory",
    [string]$ReleaseName = "subnetory",
    [string]$ApplicationDeployment = "subnetory",
    [int]$ApplicationPort = 8080,
    [string]$Database = "subnetory",
    [string]$DatabaseUser = "subnetory",
    [string]$ExpectedSha256 = "",
    [string]$Kubectl = "kubectl",
    [switch]$SkipSafetyBackup,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true

function Get-ExpectedFlywayVersion {
    $RootPath = Split-Path -Parent $PSScriptRoot
    $MigrationPath = Join-Path $RootPath "backend\src\main\resources\db\migration"
    $Migrations = @(Get-ChildItem -LiteralPath $MigrationPath -Filter "V*.sql" |
        ForEach-Object {
            $Match = [regex]::Match($_.Name, '^V(?<Version>[0-9]+)__.+\.sql$')
            if ($Match.Success) {
                [int]$Match.Groups["Version"].Value
            }
        } |
        Sort-Object)

    if ($Migrations.Count -lt 1) {
        throw "Aucune migration Flyway versionnée trouvée."
    }

    return [string]$Migrations[-1]
}

function Get-SingleRunningPod {
    param([Parameter(Mandatory = $true)][string]$Component)

    $selector = (
        "app.kubernetes.io/instance=$ReleaseName," +
        "app.kubernetes.io/component=$Component"
    )
    $json = & $Kubectl get pods `
        --namespace $Namespace `
        --selector $selector `
        --output json | ConvertFrom-Json

    $pods = @($json.items | Where-Object {
        $_.status.phase -eq "Running" -and
        -not ($_.metadata.PSObject.Properties.Name -contains "deletionTimestamp")
    })

    if ($pods.Count -ne 1) {
        throw "Un unique pod '$Component' Running est attendu, trouvé : $($pods.Count)."
    }

    return [string]$pods[0].metadata.name
}

function Read-ExpectedHash {
    param([Parameter(Mandatory = $true)][string]$ResolvedBackup)

    if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256)) {
        return $ExpectedSha256.Trim().ToUpperInvariant()
    }

    $sidecar = "$ResolvedBackup.sha256"
    if (-not (Test-Path -LiteralPath $sidecar)) {
        throw "Empreinte SHA256 absente : fournissez -ExpectedSha256 ou le fichier '$sidecar'."
    }

    $line = [System.IO.File]::ReadAllText($sidecar).Trim()
    $candidate = ($line -split "\s+", 2)[0]
    return $candidate.ToUpperInvariant()
}

if ($null -eq (Get-Command $Kubectl -ErrorAction SilentlyContinue)) {
    throw "kubectl est introuvable : $Kubectl"
}

$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile -ErrorAction Stop).Path
$backupInfo = Get-Item -LiteralPath $resolvedBackup
if ($backupInfo.Length -le 0) {
    throw "Le fichier de sauvegarde est vide."
}

$expectedHash = Read-ExpectedHash -ResolvedBackup $resolvedBackup
$actualHash = (Get-FileHash -LiteralPath $resolvedBackup -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualHash -ne $expectedHash) {
    throw "L'empreinte SHA256 du dump ne correspond pas à la valeur attendue."
}

$postgresPod = Get-SingleRunningPod -Component "postgresql"
$originalReplicasText = & $Kubectl get deployment $ApplicationDeployment `
    --namespace $Namespace `
    --output "jsonpath={.spec.replicas}"
$originalReplicas = [int]$originalReplicasText
if ($originalReplicas -ne 1) {
    throw "Le déploiement applicatif doit avoir exactement une réplique avant restauration."
}

if (-not $Force) {
    $confirmation = "RESTORE $Namespace/$postgresPod/$Database"
    $entered = Read-Host "Tapez '$confirmation' pour confirmer la restauration destructive"
    if ($entered -cne $confirmation) {
        throw "Restauration annulée : confirmation incorrecte."
    }
}

if (-not $SkipSafetyBackup) {
    $safetyDirectory = Join-Path $backupInfo.Directory.FullName "safety-before-restore"
    & (Join-Path $PSScriptRoot "backup-postgres-kubernetes.ps1") `
        -Namespace $Namespace `
        -ReleaseName $ReleaseName `
        -Database $Database `
        -DatabaseUser $DatabaseUser `
        -OutputDirectory $safetyDirectory `
        -Kubectl $Kubectl
}

$remotePath = "/tmp/subnetory-restore-$([Guid]::NewGuid().ToString('N')).dump"
$applicationScaledDown = $false
$restoreSucceeded = $false

try {
    Write-Host "Copie du dump dans le pod PostgreSQL avec kubectl cp."
    Push-Location $backupInfo.Directory.FullName
    try {
        & $Kubectl cp `
            $backupInfo.Name `
            "${Namespace}/${postgresPod}:${remotePath}"
    }
    finally {
        Pop-Location
    }

    & $Kubectl exec --namespace $Namespace $postgresPod -- `
        pg_restore --list $remotePath | Out-Null

    Write-Host "Arrêt de l'unique réplique applicative avant restauration."
    & $Kubectl scale deployment $ApplicationDeployment `
        --namespace $Namespace `
        --replicas=0 | Out-Null
    $applicationScaledDown = $true

    & $Kubectl rollout status deployment/$ApplicationDeployment `
        --namespace $Namespace `
        --timeout=180s | Out-Null

    Write-Host "Restauration PostgreSQL en cours."
    & $Kubectl exec --namespace $Namespace $postgresPod -- `
        pg_restore `
        --username=$DatabaseUser `
        --dbname=$Database `
        --clean `
        --if-exists `
        --no-owner `
        --no-acl `
        --exit-on-error `
        $remotePath

    $flywayVersion = (& $Kubectl exec --namespace $Namespace $postgresPod -- `
        psql `
        --username=$DatabaseUser `
        --dbname=$Database `
        --tuples-only `
        --no-align `
        --command="SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;").Trim()

    $expectedFlywayVersion = Get-ExpectedFlywayVersion
    if ($flywayVersion -ne $expectedFlywayVersion) {
        throw "Flyway V$expectedFlywayVersion attendu après restauration, obtenu : '$flywayVersion'."
    }

    & $Kubectl scale deployment $ApplicationDeployment `
        --namespace $Namespace `
        --replicas=$originalReplicas | Out-Null
    $applicationScaledDown = $false

    & $Kubectl rollout status deployment/$ApplicationDeployment `
        --namespace $Namespace `
        --timeout=300s | Out-Null

    $applicationPod = Get-SingleRunningPod -Component "application"
    $health = (& $Kubectl exec --namespace $Namespace $applicationPod -- `
        wget -qO- "http://127.0.0.1:$ApplicationPort/actuator/health/readiness") -join ""
    $healthStatus = ($health | ConvertFrom-Json).status
    if ($healthStatus -ne "UP") {
        throw "La readiness applicative n'est pas UP après restauration."
    }

    $restoreSucceeded = $true
    Write-Host "Restauration terminée : SHA256, Flyway V$expectedFlywayVersion et readiness UP validés."
}
finally {
    try {
        & $Kubectl exec --namespace $Namespace $postgresPod -- `
            rm -f -- $remotePath | Out-Null
    }
    catch {
        Write-Warning "Impossible de supprimer le dump temporaire dans le pod."
    }

    if ($applicationScaledDown -and -not $restoreSucceeded) {
        Write-Warning (
            "La restauration a échoué : le déploiement '$ApplicationDeployment' " +
            "reste volontairement à 0 réplique pour éviter des écritures sur une base partielle."
        )
    }
}
