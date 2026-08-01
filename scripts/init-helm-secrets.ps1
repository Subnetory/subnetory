#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Namespace = "subnetory",
    [string]$RuntimeSecretName = "subnetory-runtime-secrets",
    [string]$BootstrapSecretName = "subnetory-bootstrap-secrets",
    [string]$Kubectl = "kubectl",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true

function New-Base64UrlSecret {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 4096)]
        [int]$ByteCount
    )

    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Write-SecretFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Value
    )

    [System.IO.File]::WriteAllText(
        $Path,
        $Value,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Test-KubernetesSecretExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $result = & $Kubectl get secret $Name `
        --namespace $Namespace `
        --ignore-not-found `
        --output name

    return -not [string]::IsNullOrWhiteSpace(($result -join ""))
}

function Set-KubernetesSecretFromFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$FromFileArguments,
        [Parameter(Mandatory = $true)][bool]$Exists
    )

    $arguments = @(
        "create", "secret", "generic", $Name,
        "--namespace", $Namespace
    ) + $FromFileArguments

    if ($Exists) {
        & $Kubectl @arguments --dry-run=client --output yaml |
            & $Kubectl replace --filename - | Out-Null
    }
    else {
        & $Kubectl @arguments | Out-Null
    }
}

if ($null -eq (Get-Command $Kubectl -ErrorAction SilentlyContinue)) {
    throw "kubectl est introuvable : $Kubectl"
}

& $Kubectl get namespace $Namespace --output name | Out-Null

$runtimeExists = Test-KubernetesSecretExists -Name $RuntimeSecretName
$bootstrapExists = Test-KubernetesSecretExists -Name $BootstrapSecretName

if (($runtimeExists -or $bootstrapExists) -and -not $Force) {
    $existingNames = @()
    if ($runtimeExists) {
        $existingNames += $RuntimeSecretName
    }
    if ($bootstrapExists) {
        $existingNames += $BootstrapSecretName
    }

    throw (
        "Refus d'écraser les Secrets existants : " +
        ($existingNames -join ", ") +
        ". Utilisez -Force uniquement pour une rotation volontaire."
    )
}

$temporaryRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) ("subnetory-helm-secrets-" + [Guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

    $jwtPath = Join-Path $temporaryRoot "jwt-secret"
    $postgresPath = Join-Path $temporaryRoot "postgres-password"
    $adminPath = Join-Path $temporaryRoot "admin-default-password"

    Write-SecretFile -Path $jwtPath -Value (New-Base64UrlSecret -ByteCount 64)
    Write-SecretFile -Path $postgresPath -Value (New-Base64UrlSecret -ByteCount 32)
    Write-SecretFile -Path $adminPath -Value (New-Base64UrlSecret -ByteCount 24)

    Set-KubernetesSecretFromFiles `
        -Name $RuntimeSecretName `
        -Exists $runtimeExists `
        -FromFileArguments @(
            "--from-file=jwt-secret=$jwtPath",
            "--from-file=postgres-password=$postgresPath"
        )

    Set-KubernetesSecretFromFiles `
        -Name $BootstrapSecretName `
        -Exists $bootstrapExists `
        -FromFileArguments @(
            "--from-file=admin-default-password=$adminPath"
        )

    Write-Host "Secrets Kubernetes initialisés dans le namespace '$Namespace'."
    Write-Host "Aucune valeur secrète n'a été affichée."
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
