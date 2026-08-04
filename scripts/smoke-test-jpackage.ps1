#requires -Version 7.0
<#
.SYNOPSIS
  Valide l'app-image Windows Subnetory produite par make-jpackage.ps1.

.DESCRIPTION
  Le test utilise une base PostgreSQL 17 temporaire, démarre Subnetory.exe,
  vérifie la dernière migration Flyway, le changement obligatoire du mot de passe initial,
  le blocage du dashboard et le refus/retour de l'émission JWT.

  L'endpoint /actuator/health n'est pas utilisé comme sonde de démarrage,
  car il peut être protégé par Spring Security. La page /login sert de sonde.
#>

[CmdletBinding()]
param(
    [string]$ArtifactName = "subnetory-0.8.5-windows-x64",
    [switch]$KeepExtraction,
    [switch]$KeepDatabase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "`n=== $Message ===" -ForegroundColor Cyan
}

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

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )

    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Get-CsrfToken {
    param([Parameter(Mandatory = $true)][string]$Html)

    $patterns = @(
        'name="_csrf"[^>]*value="([^"]+)"',
        'value="([^"]+)"[^>]*name="_csrf"'
    )

    foreach ($pattern in $patterns) {
        $match = [regex]::Match(
            $Html,
            $pattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )

        if ($match.Success) {
            return $match.Groups[1].Value
        }
    }

    throw "Token CSRF introuvable dans la page HTML."
}

function Get-FinalPath {
    param($Response)

    if ($null -ne $Response.BaseResponse.RequestMessage -and
        $null -ne $Response.BaseResponse.RequestMessage.RequestUri) {
        return $Response.BaseResponse.RequestMessage.RequestUri.AbsolutePath
    }

    if ($null -ne $Response.BaseResponse.ResponseUri) {
        return $Response.BaseResponse.ResponseUri.AbsolutePath
    }

    throw "URI finale de la réponse introuvable."
}

function Convert-WebContentToString {
    param(
        [Parameter(Mandatory = $true)]
        $Content
    )

    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }

    if ($Content -is [System.Array] -and
        $Content.Count -gt 0 -and
        $Content[0] -is [byte]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$Content)
    }

    return [string]$Content
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$distRoot = Join-Path $repoRoot "dist\windows"
$zipPath = Join-Path $distRoot "$ArtifactName.zip"
$hashPath = Join-Path $distRoot "$ArtifactName.sha256.txt"
$smokeRoot = Join-Path $distRoot "smoke-validation"
$extractRoot = Join-Path $smokeRoot "extracted"
$stdoutPath = Join-Path $smokeRoot "subnetory.stdout.log"
$stderrPath = Join-Path $smokeRoot "subnetory.stderr.log"
$summaryPath = Join-Path $smokeRoot "SMOKE_TEST_RESULT.txt"

foreach ($path in @($zipPath, $hashPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Fichier obligatoire absent : $path"
    }
}

$docker = Get-Command "docker.exe" -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw "docker.exe est introuvable."
}

& $docker.Source info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop n'est pas disponible."
}

Write-Step "Vérification SHA256"

$expectedHash = (
    (Get-Content -LiteralPath $hashPath | Select-Object -First 1) -split '\s+'
)[0].Trim()

$actualHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash

Write-Host "Attendu : $expectedHash"
Write-Host "Calculé  : $actualHash"

if ($actualHash -ine $expectedHash) {
    throw "L'empreinte SHA256 du ZIP est invalide."
}

Write-Host "SHA256 valide." -ForegroundColor Green

Add-Type -AssemblyName System.IO.Compression.FileSystem

if (Test-Path -LiteralPath $smokeRoot) {
    Remove-Item -LiteralPath $smokeRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $extractRoot -Force | Out-Null

$dbPort = Get-FreeTcpPort
$appPort = Get-FreeTcpPort
while ($appPort -eq $dbPort) {
    $appPort = Get-FreeTcpPort
}

$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$dbContainer = "subnetory-jpackage-smoke-db-$timestamp"

$dbPassword = "Db-$([guid]::NewGuid().ToString('N'))!Aa1"
$jwtSecret = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
$bootstrapPassword = "Tmp-$([guid]::NewGuid().ToString('N'))!Aa1"
$newAdminPassword = "Validated-Subnetory-456!"

$appProcess = $null
$smokeSucceeded = $false
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

try {
    Write-Step "Extraction de l'artefact"

    [System.IO.Compression.ZipFile]::ExtractToDirectory(
        $zipPath,
        $extractRoot
    )

    $appRoot = Join-Path $extractRoot $ArtifactName
    $exePath = Join-Path $appRoot "Subnetory.exe"
    $runtimeJava = Join-Path $appRoot "runtime\bin\java.exe"

    foreach ($path in @($exePath, $runtimeJava)) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Fichier extrait absent : $path"
        }
    }

    Write-Host "Launcher     : $exePath"
    Write-Host "Runtime Java : $runtimeJava"

    Write-Step "Démarrage de PostgreSQL 17 isolé"

    $containerId = (
        & $docker.Source run `
            --detach `
            --name $dbContainer `
            --publish "127.0.0.1:${dbPort}:5432" `
            --env "POSTGRES_DB=subnetory" `
            --env "POSTGRES_USER=subnetory" `
            --env "POSTGRES_PASSWORD=$dbPassword" `
            postgres:17-alpine
    ).Trim()

    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Impossible de démarrer PostgreSQL."
    }

    $databaseReady = $false

    for ($attempt = 1; $attempt -le 60; $attempt++) {
        & $docker.Source exec `
            $dbContainer `
            pg_isready `
            -U subnetory `
            -d subnetory *> $null

        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }

        Start-Sleep -Seconds 1
    }

    if (-not $databaseReady) {
        throw "PostgreSQL n'est pas devenu disponible."
    }

    Write-Host "PostgreSQL disponible sur le port $dbPort." -ForegroundColor Green

    Write-Step "Démarrage de Subnetory.exe"

    $environmentValues = @{
        "SPRING_DATASOURCE_URL" =
            "jdbc:postgresql://127.0.0.1:${dbPort}/subnetory?stringtype=unspecified"
        "SPRING_DATASOURCE_USERNAME" = "subnetory"
        "SPRING_DATASOURCE_PASSWORD" = $dbPassword
        "SUBNETORY_JWT_SECRET" = $jwtSecret
        "SUBNETORY_ADMIN_DEFAULT_PASSWORD" = $bootstrapPassword
        "SERVER_PORT" = $appPort.ToString()
        "SPRING_THYMELEAF_CACHE" = "false"
    }

    $previousEnvironment = @{}

    foreach ($name in $environmentValues.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
            $name,
            "Process"
        )

        [Environment]::SetEnvironmentVariable(
            $name,
            $environmentValues[$name],
            "Process"
        )
    }

    try {
        $appProcess = Start-Process `
            -FilePath $exePath `
            -WorkingDirectory $appRoot `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -WindowStyle Hidden `
            -PassThru
    }
    finally {
        foreach ($name in $environmentValues.Keys) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $previousEnvironment[$name],
                "Process"
            )
        }
    }

    $baseUrl = "http://127.0.0.1:$appPort"
    $applicationReady = $false

    for ($attempt = 1; $attempt -le 120; $attempt++) {
        $appProcess.Refresh()

        if ($appProcess.HasExited) {
            throw "Subnetory.exe s'est arrêté avec le code $($appProcess.ExitCode)."
        }

        try {
            $readiness = Invoke-WebRequest `
                -Uri "$baseUrl/login" `
                -SkipHttpErrorCheck `
                -TimeoutSec 3

            if ($readiness.StatusCode -eq 200 -and
                $readiness.Content -match "Subnetory") {
                $applicationReady = $true
                break
            }
        }
        catch {
            # L'application est encore en cours de démarrage.
        }

        Start-Sleep -Seconds 1
    }

    if (-not $applicationReady) {
        throw "La page /login n'est pas devenue disponible."
    }

    Write-Host "Application disponible : $baseUrl" -ForegroundColor Green

    $expectedFlywayVersion = Get-ExpectedFlywayVersion
    Write-Step "Vérification Flyway V$expectedFlywayVersion"

    $flywayState = (
        & $docker.Source exec `
            $dbContainer `
            psql `
            -U subnetory `
            -d subnetory `
            -tAc `
            "SELECT version || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
    ).Trim()

    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de lire flyway_schema_history."
    }

    Write-Host "Dernière migration : $flywayState"

    if ($flywayState -ne "$expectedFlywayVersion|t") {
        throw "Flyway V$expectedFlywayVersion n'est pas la dernière migration valide."
    }

    Write-Step "Premier login et redirection obligatoire"

    $webSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

    $loginPage = Invoke-WebRequest `
        -Uri "$baseUrl/login" `
        -WebSession $webSession

    $loginCsrf = Get-CsrfToken -Html $loginPage.Content

    $forcedPage = Invoke-WebRequest `
        -Uri "$baseUrl/login" `
        -Method Post `
        -WebSession $webSession `
        -Body @{
            username = "admin"
            password = $bootstrapPassword
            _csrf = $loginCsrf
        }

    $forcedPath = Get-FinalPath -Response $forcedPage
    Write-Host "Destination : $forcedPath"

    if ($forcedPath -ne "/profile/change-password-required") {
        throw "Le premier login n'a pas imposé le changement de mot de passe."
    }

    Write-Step "Blocage du dashboard"

    $blockedDashboard = Invoke-WebRequest `
        -Uri "$baseUrl/" `
        -WebSession $webSession

    $blockedPath = Get-FinalPath -Response $blockedDashboard
    Write-Host "Destination : $blockedPath"

    if ($blockedPath -ne "/profile/change-password-required") {
        throw "Le dashboard est accessible avant le changement du mot de passe."
    }

    Write-Step "Refus JWT avec le mot de passe temporaire"

    $temporaryTokenRequest = @{
        username = "admin"
        password = $bootstrapPassword
    } | ConvertTo-Json -Compress

    $forbiddenToken = Invoke-WebRequest `
        -Uri "$baseUrl/api/v1/auth/token" `
        -Method Post `
        -ContentType "application/json" `
        -Body $temporaryTokenRequest `
        -SkipHttpErrorCheck

    if ($forbiddenToken.StatusCode -ne 403) {
        throw "HTTP 403 attendu, HTTP $($forbiddenToken.StatusCode) reçu."
    }

    $forbiddenBody = Convert-WebContentToString `
        -Content $forbiddenToken.Content

    Write-Host "HTTP          : $($forbiddenToken.StatusCode)"
    Write-Host "Content-Type  : $($forbiddenToken.Headers['Content-Type'])"
    Write-Host "Corps API     : $forbiddenBody"

    $forbiddenProblem = $forbiddenBody | ConvertFrom-Json
    $apiErrorCode = $null

    if ($forbiddenProblem.PSObject.Properties.Name -contains "code") {
        $apiErrorCode = [string]$forbiddenProblem.code
    }
    elseif (
        $forbiddenProblem.PSObject.Properties.Name -contains "properties" -and
        $null -ne $forbiddenProblem.properties -and
        $forbiddenProblem.properties.PSObject.Properties.Name -contains "code"
    ) {
        $apiErrorCode = [string]$forbiddenProblem.properties.code
    }

    Write-Host "Code API      : $apiErrorCode"

    if ($apiErrorCode -ne "PASSWORD_CHANGE_REQUIRED") {
        throw "Le code PASSWORD_CHANGE_REQUIRED est absent de la reponse API."
    }

    Write-Step "Changement obligatoire du mot de passe"

    $changePage = Invoke-WebRequest `
        -Uri "$baseUrl/profile/change-password-required" `
        -WebSession $webSession

    $changeCsrf = Get-CsrfToken -Html $changePage.Content

    $changedPage = Invoke-WebRequest `
        -Uri "$baseUrl/profile/change-password-required" `
        -Method Post `
        -WebSession $webSession `
        -Body @{
            currentPassword = $bootstrapPassword
            newPassword = $newAdminPassword
            confirmPassword = $newAdminPassword
            _csrf = $changeCsrf
        }

    $changedPath = Get-FinalPath -Response $changedPage
    Write-Host "Destination : $changedPath"

    if ($changedPath -ne "/") {
        throw "L'accès normal n'a pas été restauré après le changement."
    }

    Write-Step "Accès dashboard après changement"

    $dashboard = Invoke-WebRequest `
        -Uri "$baseUrl/" `
        -WebSession $webSession

    if ($dashboard.StatusCode -ne 200 -or
        (Get-FinalPath -Response $dashboard) -ne "/") {
        throw "Le dashboard reste inaccessible après le changement."
    }

    Write-Step "Émission JWT après changement"

    $newTokenRequest = @{
        username = "admin"
        password = $newAdminPassword
    } | ConvertTo-Json -Compress

    $tokenResponse = Invoke-WebRequest `
        -Uri "$baseUrl/api/v1/auth/token" `
        -Method Post `
        -ContentType "application/json" `
        -Body $newTokenRequest `
        -SkipHttpErrorCheck

    if ($tokenResponse.StatusCode -ne 200) {
        throw "Émission JWT inattendue : HTTP $($tokenResponse.StatusCode)."
    }

    $tokenBody = Convert-WebContentToString `
        -Content $tokenResponse.Content

    $token = $tokenBody | ConvertFrom-Json

    if ([string]::IsNullOrWhiteSpace($token.accessToken)) {
        throw "Le JWT est absent après le changement du mot de passe."
    }

    $summary = @(
        "Subnetory Sprint 2.30 - Windows app-image smoke test",
        "",
        "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')",
        "Git commit: $((git -C $repoRoot rev-parse HEAD).Trim())",
        "Archive: $([System.IO.Path]::GetFileName($zipPath))",
        "SHA256: $actualHash",
        "PostgreSQL image: postgres:17-alpine",
        "Flyway latest migration: $flywayState",
        "Login page readiness: PASS",
        "Initial login forced redirect: PASS",
        "Direct dashboard access blocked: PASS",
        "Temporary-password JWT rejection: PASS",
        "API error code: $apiErrorCode",
        "Mandatory password change: PASS",
        "Dashboard access after change: PASS",
        "JWT after password change: PASS",
        "",
        "No secret or generated password is recorded in this report."
    )

    [System.IO.File]::WriteAllLines(
        $summaryPath,
        [string[]]$summary,
        $utf8NoBom
    )

    $smokeSucceeded = $true
}
catch {
    Write-Host "`n=== ÉCHEC DU SMOKE TEST ===" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red

    if (Test-Path -LiteralPath $stdoutPath) {
        Write-Host "`n--- Dernières lignes stdout ---" -ForegroundColor Yellow
        Get-Content -LiteralPath $stdoutPath -Tail 100
    }

    if (Test-Path -LiteralPath $stderrPath) {
        Write-Host "`n--- Dernières lignes stderr ---" -ForegroundColor Yellow
        Get-Content -LiteralPath $stderrPath -Tail 100
    }

    throw
}
finally {
    Write-Step "Nettoyage"

    if ($null -ne $appProcess) {
        $appProcess.Refresh()

        if (-not $appProcess.HasExited) {
            Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
            $appProcess.WaitForExit(5000) | Out-Null
        }
    }

    if (-not $KeepDatabase) {
        & $docker.Source rm --force $dbContainer *> $null
    }
    else {
        Write-Host "Base conservée : $dbContainer" -ForegroundColor Yellow
    }

    if (-not $KeepExtraction -and
        (Test-Path -LiteralPath $extractRoot)) {
        Remove-Item -LiteralPath $extractRoot -Recurse -Force
    }
}

if (-not $smokeSucceeded) {
    throw "Le smoke test n'a pas été validé."
}

Write-Host "`n=== SMOKE TEST WINDOWS RÉUSSI ===" -ForegroundColor Green
Get-Content -LiteralPath $summaryPath
