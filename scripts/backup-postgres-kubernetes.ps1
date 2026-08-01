#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Namespace = "subnetory",
    [string]$ReleaseName = "subnetory",
    [string]$Database = "subnetory",
    [string]$DatabaseUser = "subnetory",
    [string]$OutputDirectory = "",
    [string]$Kubectl = "kubectl"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true

function Get-SingleRunningPod {
    $selector = (
        "app.kubernetes.io/instance=$ReleaseName," +
        "app.kubernetes.io/component=postgresql"
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
        throw "Un unique pod PostgreSQL Running est attendu, trouvé : $($pods.Count)."
    }

    return [string]$pods[0].metadata.name
}

if ($null -eq (Get-Command $Kubectl -ErrorAction SilentlyContinue)) {
    throw "kubectl est introuvable : $Kubectl"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\backups\kubernetes"
}

$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$pod = Get-SingleRunningPod
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$fileName = "subnetory-$Namespace-$timestamp.dump"
$finalPath = Join-Path $OutputDirectory $fileName
$partialPath = "$finalPath.partial"
$hashPath = "$finalPath.sha256"
$remotePath = "/tmp/subnetory-backup-$([Guid]::NewGuid().ToString('N')).dump"
$succeeded = $false

try {
    Write-Host "Création du dump PostgreSQL compressé dans le pod '$pod'."
    & $Kubectl exec --namespace $Namespace $pod -- `
        pg_dump `
        --username=$DatabaseUser `
        --dbname=$Database `
        --format=custom `
        --compress=6 `
        --clean `
        --if-exists `
        --no-owner `
        --no-acl `
        --file=$remotePath

    & $Kubectl exec --namespace $Namespace $pod -- `
        pg_restore --list $remotePath | Out-Null

    Write-Host "Copie du dump hors du pod avec kubectl cp."
    Push-Location $OutputDirectory
    try {
        & $Kubectl cp `
            "${Namespace}/${pod}:${remotePath}" `
            ([System.IO.Path]::GetFileName($partialPath))
    }
    finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $partialPath)) {
        throw "kubectl cp n'a pas créé le fichier attendu."
    }

    $fileInfo = Get-Item -LiteralPath $partialPath
    if ($fileInfo.Length -le 0) {
        throw "Le dump copié est vide."
    }

    Move-Item -LiteralPath $partialPath -Destination $finalPath
    $hash = (Get-FileHash -LiteralPath $finalPath -Algorithm SHA256).Hash.ToUpperInvariant()
    [System.IO.File]::WriteAllText(
        $hashPath,
        "$hash  $fileName`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    $succeeded = $true
    Write-Host "Sauvegarde Kubernetes terminée."
    Write-Host "Fichier : $finalPath"
    Write-Host "SHA256 : $hash"
}
finally {
    try {
        & $Kubectl exec --namespace $Namespace $pod -- `
            rm -f -- $remotePath | Out-Null
    }
    catch {
        Write-Warning "Impossible de supprimer la copie temporaire dans le pod."
    }

    if (-not $succeeded) {
        foreach ($path in @($partialPath, $finalPath, $hashPath)) {
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force
            }
        }
    }
}
