#requires -Version 7.0
<#
.SYNOPSIS
  Genere une distribution Windows portable de Subnetory avec jlink et jpackage.

.DESCRIPTION
  Le script construit le JAR Spring Boot, cree un runtime Java 21 dedie,
  genere une app-image Windows, une archive ZIP et les empreintes SHA256.

  PostgreSQL reste externe au package Sprint 2.29.
  Le MSI est optionnel et requiert WiX Toolset 3.

.EXAMPLE
  pwsh.exe -File scripts/make-jpackage.ps1

.EXAMPLE
  pwsh.exe -File scripts/make-jpackage.ps1 -SkipBuild

.EXAMPLE
  pwsh.exe -File scripts/make-jpackage.ps1 -Msi
#>

[CmdletBinding()]
param(
    [string]$OutputDir,
    [switch]$SkipBuild,
    [switch]$Msi,
    [switch]$KeepWork
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "OK  $Message" -ForegroundColor Green
}

function Assert-PathExists {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Chemin obligatoire absent : $Path"
    }
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Label
    )

    Write-Step $Label
    & $FilePath @Arguments

    if ($LASTEXITCODE -ne 0) {
        throw "$Label a echoue avec le code $LASTEXITCODE."
    }
}

function Get-PomVersion {
    param([Parameter(Mandatory = $true)][string]$PomPath)

    $content = [System.IO.File]::ReadAllText($PomPath)
    $match = [regex]::Match(
        $content,
        '<artifactId>\s*subnetory\s*</artifactId>\s*<version>([^<]+)</version>',
        [System.Text.RegularExpressions.RegexOptions]::Singleline)

    if (-not $match.Success) {
        throw "Version Maven de Subnetory introuvable dans $PomPath"
    }

    return $match.Groups[1].Value.Trim()
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$sourceRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$backendRoot = Join-Path $sourceRoot 'backend'
$pomPath = Join-Path $backendRoot 'pom.xml'
$mvnw = Join-Path $backendRoot 'mvnw.cmd'
$targetRoot = Join-Path $backendRoot 'target'
$workRoot = Join-Path $targetRoot 'jpackage'
$inputDir = Join-Path $workRoot 'input'
$runtimeDir = Join-Path $workRoot 'runtime'
$jpackageOutput = Join-Path $workRoot 'output'

Assert-PathExists -Path $pomPath
Assert-PathExists -Path $mvnw

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $sourceRoot 'dist\windows'
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = Join-Path $sourceRoot $OutputDir
}

$mavenVersion = Get-PomVersion -PomPath $pomPath
$appVersion = $mavenVersion -replace '-SNAPSHOT$', ''

if ($appVersion -notmatch '^\d+(?:\.\d+){0,3}$') {
    throw "Version jpackage invalide : $appVersion"
}

$toolNames = @('java', 'jar', 'jlink', 'jpackage')
$tools = @{}

foreach ($toolName in $toolNames) {
    $command = Get-Command "$toolName.exe" -ErrorAction SilentlyContinue

    if ($null -eq $command) {
        throw "Outil JDK introuvable dans le PATH : $toolName.exe"
    }

    $tools[$toolName] = $command.Source
}

$jdkBin = Split-Path $tools['jpackage'] -Parent
$jdkHome = Split-Path $jdkBin -Parent
$jmodsPath = Join-Path $jdkHome 'jmods'

Assert-PathExists -Path $jmodsPath

$jpackageVersion = (& $tools['jpackage'] --version 2>&1 |
    Select-Object -First 1).ToString().Trim()

if ($jpackageVersion -notmatch '^21(?:\.|$)') {
    throw "Le packaging requiert jpackage 21. Version detectee : $jpackageVersion"
}

$candle = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
$light = Get-Command 'light.exe' -ErrorAction SilentlyContinue
$hasWix3 = $null -ne $candle -and $null -ne $light

if ($Msi -and -not $hasWix3) {
    throw "Le parametre -Msi requiert WiX Toolset 3 : candle.exe et light.exe sont absents."
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}

New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
New-Item -ItemType Directory -Path $jpackageOutput -Force | Out-Null

Write-Host ""
Write-Host "Subnetory Windows packaging" -ForegroundColor Green
Write-Host "Source         : $sourceRoot"
Write-Host "Version Maven  : $mavenVersion"
Write-Host "Version native : $appVersion"
Write-Host "JDK            : $jdkHome"
Write-Host "jpackage       : $jpackageVersion"
Write-Host "Sortie         : $OutputDir"
Write-Host "WiX 3          : $hasWix3"
Write-Host ""

if (-not $SkipBuild) {
    Push-Location $backendRoot
    try {
        Invoke-Native `
            -FilePath $mvnw `
            -Arguments @('-DskipTests', 'package') `
            -Label 'Construction du JAR Spring Boot'
    } finally {
        Pop-Location
    }
} else {
    Write-Step 'Construction Maven ignoree avec -SkipBuild'
}

$jarFiles = @(
    Get-ChildItem -LiteralPath $targetRoot -Filter 'subnetory-*.jar' -File |
        Where-Object {
            $_.Name -notlike '*.original' -and
            $_.Name -notlike '*-sources.jar' -and
            $_.Name -notlike '*-javadoc.jar'
        }
)

if ($jarFiles.Count -ne 1) {
    $jarFiles | Select-Object Name, Length, FullName | Format-Table
    throw "Un unique JAR applicatif Subnetory est attendu."
}

$jar = $jarFiles[0]
$inputJar = Join-Path $inputDir $jar.Name

Copy-Item -LiteralPath $jar.FullName -Destination $inputJar -Force
Write-Ok "JAR place dans le staging : $($jar.Name)"

$modules = @(
    'java.se',
    'jdk.charsets',
    'jdk.crypto.cryptoki',
    'jdk.crypto.ec',
    'jdk.crypto.mscapi',
    'jdk.httpserver',
    'jdk.jfr',
    'jdk.localedata',
    'jdk.management',
    'jdk.management.agent',
    'jdk.naming.dns',
    'jdk.naming.rmi',
    'jdk.net',
    'jdk.security.auth',
    'jdk.security.jgss',
    'jdk.unsupported',
    'jdk.zipfs'
)

$jlinkArguments = @(
    '--module-path', $jmodsPath,
    '--add-modules', ($modules -join ','),
    '--output', $runtimeDir,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=zip-6'
)

Invoke-Native `
    -FilePath $tools['jlink'] `
    -Arguments $jlinkArguments `
    -Label 'Creation du runtime Java 21 dedie'

Assert-PathExists -Path (Join-Path $runtimeDir 'bin\java.exe')

$appImageArguments = @(
    '--type', 'app-image',
    '--dest', $jpackageOutput,
    '--input', $inputDir,
    '--name', 'Subnetory',
    '--app-version', $appVersion,
    '--vendor', 'Subnetory',
    '--description', 'Subnetory IP Address Management',
    '--main-jar', $jar.Name,
    '--runtime-image', $runtimeDir,
    '--win-console',
    '--java-options', '-XX:MaxRAMPercentage=75',
    '--java-options', '-Dfile.encoding=UTF-8'
)

Invoke-Native `
    -FilePath $tools['jpackage'] `
    -Arguments $appImageArguments `
    -Label 'Creation de l''app-image Windows'

$generatedAppImage = Join-Path $jpackageOutput 'Subnetory'
Assert-PathExists -Path $generatedAppImage
Assert-PathExists -Path (Join-Path $generatedAppImage 'Subnetory.exe')

$artifactBaseName = "subnetory-$appVersion-windows-x64"
$finalAppImage = Join-Path $OutputDir $artifactBaseName
$zipPath = Join-Path $OutputDir "$artifactBaseName.zip"
$hashPath = Join-Path $OutputDir "$artifactBaseName.sha256.txt"
$manifestPath = Join-Path $OutputDir "$artifactBaseName.manifest.txt"

foreach ($path in @($finalAppImage, $zipPath, $hashPath, $manifestPath)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

Move-Item -LiteralPath $generatedAppImage -Destination $finalAppImage

$readmePath = Join-Path $finalAppImage 'README-WINDOWS.txt'
$readme = @(
    'Subnetory - distribution Windows portable',
    '',
    'Prerequis d execution :',
    '- PostgreSQL 17 accessible depuis cette machine',
    '- variables SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME et SPRING_DATASOURCE_PASSWORD',
    '- variable SUBNETORY_JWT_SECRET d au moins 32 caracteres',
    '- variable SUBNETORY_ADMIN_DEFAULT_PASSWORD pour le premier demarrage',
    '',
    'Demarrage :',
    '  .\Subnetory.exe',
    '',
    'URL par defaut : http://localhost:8080',
    '',
    'Lors du premier login, le compte admin est oblige de remplacer le mot de passe de bootstrap.',
    'Aucun acces Web ou JWT n est autorise avant ce changement.'
)

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($readmePath, [string[]]$readme, $utf8NoBom)

$manifest = Get-ChildItem -LiteralPath $finalAppImage -Recurse -File |
    Sort-Object FullName |
    ForEach-Object {
        $relative = [System.IO.Path]::GetRelativePath($finalAppImage, $_.FullName)
        "$relative`t$($_.Length)"
    }

[System.IO.File]::WriteAllLines(
    $manifestPath,
    [string[]]$manifest,
    $utf8NoBom)

Add-Type -AssemblyName System.IO.Compression.FileSystem

[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $finalAppImage,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true)

Assert-PathExists -Path $zipPath

$hashLines = [System.Collections.Generic.List[string]]::new()
$zipHash = Get-FileHash -LiteralPath $zipPath -Algorithm SHA256
$hashLines.Add("$($zipHash.Hash)  $([System.IO.Path]::GetFileName($zipPath))")

$msiPath = $null

if ($Msi) {
    $msiStart = Get-Date

    $msiArguments = @(
        '--type', 'msi',
        '--dest', $OutputDir,
        '--app-image', $finalAppImage,
        '--name', 'Subnetory',
        '--app-version', $appVersion,
        '--vendor', 'Subnetory',
        '--description', 'Subnetory IP Address Management',
        '--win-dir-chooser',
        '--win-menu',
        '--win-shortcut',
        '--win-upgrade-uuid', '56d6a2c0-f8f2-4db3-9c24-90b1ec089b8b'
    )

    Invoke-Native `
        -FilePath $tools['jpackage'] `
        -Arguments $msiArguments `
        -Label 'Creation de l''installateur MSI'

    $msiPath = Get-ChildItem -LiteralPath $OutputDir -Filter '*.msi' -File |
        Where-Object { $_.LastWriteTime -ge $msiStart } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $msiPath) {
        throw "jpackage a termine sans produire de MSI identifiable."
    }

    $msiHash = Get-FileHash -LiteralPath $msiPath.FullName -Algorithm SHA256
    $hashLines.Add("$($msiHash.Hash)  $($msiPath.Name)")
}

[System.IO.File]::WriteAllLines(
    $hashPath,
    [string[]]$hashLines,
    $utf8NoBom)

$appSize = (
    Get-ChildItem -LiteralPath $finalAppImage -Recurse -File |
        Measure-Object Length -Sum
).Sum

$zipInfo = Get-Item -LiteralPath $zipPath
$runtimeSize = (
    Get-ChildItem -LiteralPath $runtimeDir -Recurse -File |
        Measure-Object Length -Sum
).Sum

Write-Host ""
Write-Host "Packaging Windows termine" -ForegroundColor Green
Write-Host "Version          : $appVersion"
Write-Host "Runtime Java     : $([math]::Round($runtimeSize / 1MB, 2)) Mo"
Write-Host "App-image        : $finalAppImage"
Write-Host "Taille app-image : $([math]::Round($appSize / 1MB, 2)) Mo"
Write-Host "Archive ZIP      : $zipPath"
Write-Host "Taille ZIP       : $([math]::Round($zipInfo.Length / 1MB, 2)) Mo"
Write-Host "SHA256           : $hashPath"
Write-Host "Manifest         : $manifestPath"

if ($null -ne $msiPath) {
    Write-Host "MSI              : $($msiPath.FullName)"
}

if (-not $KeepWork) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
    Write-Ok 'Repertoire de travail supprime'
} else {
    Write-Host "Repertoire de travail conserve : $workRoot" -ForegroundColor Yellow
}
