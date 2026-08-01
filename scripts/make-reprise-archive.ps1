#requires -Version 7.0
<#
.SYNOPSIS
  Genere une archive complete et portable de reprise Subnetory depuis le commit Git courant.

.DESCRIPTION
  Le script exige un working tree propre et archive HEAD avec :
  git -c core.autocrlf=false archive

  L'archive contient uniquement les fichiers suivis par Git, sous un prefixe racine.
  Chaque entree est controlee pour la portabilite. Les fichiers temoins sont compares
  octet par octet avec les blobs Git de HEAD. Le SHA256 et le manifeste sont produits
  a cote du ZIP, sans modifier l'archive.

.EXAMPLE
  pwsh.exe -File scripts/make-reprise-archive.ps1

.EXAMPLE
  pwsh.exe -File scripts/make-reprise-archive.ps1 -OutputDir C:\Users\me\Downloads
#>

[CmdletBinding()]
param(
    [string]$Version,
    [string]$OutputDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "OK  $Message" -ForegroundColor Green
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git.exe'
    $startInfo.WorkingDirectory = $RepositoryPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
    $startInfo.CreateNoWindow = $true

    $startInfo.ArgumentList.Add('-C')
    $startInfo.ArgumentList.Add($RepositoryPath)

    foreach ($argument in $Arguments) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo

    if (-not $process.Start()) {
        throw 'Impossible de demarrer git.exe.'
    }

    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw (
            "Echec Git : git -C `"$RepositoryPath`" $($Arguments -join ' ')" +
            "`nCode retour : $($process.ExitCode)" +
            "`nSTDOUT :" +
            "`n$stdout" +
            "`nSTDERR :" +
            "`n$stderr"
        )
    }

    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        Write-Host $stderr.TrimEnd()
    }

    return $stdout.TrimEnd()
}

function Export-GitBlob {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryPath,
        [Parameter(Mandatory = $true)][string]$ObjectSpec,
        [Parameter(Mandatory = $true)][string]$DestinationPath
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git.exe'
    $startInfo.WorkingDirectory = $RepositoryPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
    $startInfo.CreateNoWindow = $true

    $startInfo.ArgumentList.Add('-C')
    $startInfo.ArgumentList.Add($RepositoryPath)
    $startInfo.ArgumentList.Add('cat-file')
    $startInfo.ArgumentList.Add('blob')
    $startInfo.ArgumentList.Add($ObjectSpec)

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo

    if (-not $process.Start()) {
        throw 'Impossible de demarrer git.exe pour exporter un blob.'
    }

    $destination = [System.IO.File]::Open(
        $DestinationPath,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )

    try {
        $process.StandardOutput.BaseStream.CopyTo($destination)
    }
    finally {
        $destination.Dispose()
    }

    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw (
            "Impossible d'exporter le blob Git $ObjectSpec." +
            "`nCode retour : $($process.ExitCode)" +
            "`nSTDERR :" +
            "`n$stderr"
        )
    }
}

function Get-Sha256Hex {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [byte[]]$Bytes
    )

    $sha256 = [System.Security.Cryptography.SHA256]::Create()

    try {
        return [Convert]::ToHexString(
            $sha256.ComputeHash($Bytes)
        )
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-EntryBytes {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchiveEntry]$Entry
    )

    $stream = $Entry.Open()
    $memory = [System.IO.MemoryStream]::new()

    try {
        $stream.CopyTo($memory)
        $bytes = $memory.ToArray()
        Write-Output -NoEnumerate $bytes
    }
    finally {
        $stream.Dispose()
        $memory.Dispose()
    }
}

function Get-LineEndingSummary {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $crlf = 0
    $lfOnly = 0
    $crOnly = 0

    for ($index = 0; $index -lt $Bytes.Length; $index++) {
        if ($Bytes[$index] -eq 13) {
            if (
                $index + 1 -lt $Bytes.Length -and
                $Bytes[$index + 1] -eq 10
            ) {
                $crlf++
                $index++
            }
            else {
                $crOnly++
            }
        }
        elseif ($Bytes[$index] -eq 10) {
            $lfOnly++
        }
    }

    return "CRLF=$crlf; LF=$lfOnly; CR=$crOnly"
}

function Assert-PortableEntryPath {
    param(
        [Parameter(Mandatory = $true)][string]$EntryPath,
        [Parameter(Mandatory = $true)][string]$RootPrefix
    )

    if ($EntryPath.Contains('\')) {
        throw "Entree ZIP non portable (separateur inverse) : $EntryPath"
    }

    if (-not $EntryPath.StartsWith($RootPrefix, [System.StringComparison]::Ordinal)) {
        throw "Entree ZIP hors prefixe racine : $EntryPath"
    }

    if ($EntryPath.Contains('//')) {
        throw "Entree ZIP non portable (separateur double) : $EntryPath"
    }

    $relative = $EntryPath.Substring($RootPrefix.Length).TrimEnd('/')

    if ([string]::IsNullOrEmpty($relative)) {
        return
    }

    if ($relative.StartsWith('/') -or $relative -match '^[A-Za-z]:') {
        throw "Entree ZIP absolue interdite : $EntryPath"
    }

    $segments = $relative.Split('/')

    foreach ($segment in $segments) {
        if (
            [string]::IsNullOrWhiteSpace($segment) -or
            $segment -eq '.' -or
            $segment -eq '..'
        ) {
            throw "Segment ZIP non portable : $EntryPath"
        }

        if ($segment.IndexOfAny([char[]]'<>:"\|?*') -ge 0) {
            throw "Caractere Windows interdit dans l'entree ZIP : $EntryPath"
        }

        if ($segment.EndsWith('.') -or $segment.EndsWith(' ')) {
            throw "Segment ZIP terminant par un point ou un espace : $EntryPath"
        }

        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($segment)

        if ($baseName -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') {
            throw "Nom reserve Windows dans l'entree ZIP : $EntryPath"
        }
    }
}

function Test-ForbiddenTrackedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $segments = $RelativePath.Split('/')

    foreach ($segment in $segments) {
        if (
            $segment -eq '.git' -or
            $segment -eq 'secrets' -or
            $segment -eq 'backups' -or
            $segment -eq 'target' -or
            $segment -eq 'dist'
        ) {
            return $true
        }
    }

    $name = $segments[-1]

    if ($name -eq '.env') {
        return $true
    }

    if ($name -like '.env.*' -and $name -ne '.env.example') {
        return $true
    }

    if ($name -like '*.sql.gz' -or $name -like '*.dump') {
        return $true
    }

    return $false
}

function Get-PomVersionFromGit {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryPath
    )

    $pomContent = Invoke-Git `
        -RepositoryPath $RepositoryPath `
        -Arguments @('show', 'HEAD:backend/pom.xml')

    $matches = [regex]::Matches(
        $pomContent,
        '<version>([^<]+)</version>'
    )

    if ($matches.Count -lt 2) {
        throw 'Version Maven introuvable dans le blob Git backend/pom.xml.'
    }

    return $matches[1].Groups[1].Value
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$source = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$tempRoot = Join-Path `
    ([System.IO.Path]::GetTempPath()) `
    ("subnetory-reprise-archive-" + [guid]::NewGuid().ToString('N'))

$zipArchive = $null

try {
    $null = Get-Command git.exe -ErrorAction Stop

    $gitTopLevel = (
        Invoke-Git `
            -RepositoryPath $source `
            -Arguments @('rev-parse', '--show-toplevel')
    ).Trim()

    if (
        -not [System.IO.Path]::GetFullPath($gitTopLevel).Equals(
            [System.IO.Path]::GetFullPath($source),
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        throw "La racine Git ne correspond pas a la racine du projet : $gitTopLevel"
    }

    $branch = (
        Invoke-Git `
            -RepositoryPath $source `
            -Arguments @('symbolic-ref', '--quiet', '--short', 'HEAD')
    ).Trim()

    $headCommit = (
        Invoke-Git `
            -RepositoryPath $source `
            -Arguments @('rev-parse', 'HEAD')
    ).Trim()

    $status = Invoke-Git `
        -RepositoryPath $source `
        -Arguments @('status', '--porcelain=v1', '--untracked-files=all')

    if (-not [string]::IsNullOrWhiteSpace($status)) {
        throw (
            'Le working tree doit etre propre. L archive porte uniquement sur HEAD.' +
            "`n$status"
        )
    }

    if ([string]::IsNullOrWhiteSpace($Version)) {
        $Version = Get-PomVersionFromGit -RepositoryPath $source
    }

    if ([string]::IsNullOrWhiteSpace($Version)) {
        throw 'Version introuvable : renseigner -Version ou verifier backend/pom.xml.'
    }

    if ([string]::IsNullOrWhiteSpace($OutputDir)) {
        $downloads = Join-Path $HOME 'Downloads'

        if (Test-Path -LiteralPath $downloads) {
            $OutputDir = $downloads
        }
        else {
            $OutputDir = $source
        }
    }

    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $archiveName = "subnetory-v$Version-complete-$timestamp"
    $rootPrefix = "$archiveName/"
    $zipPath = Join-Path $OutputDir "$archiveName.zip"
    $manifestPath = Join-Path $OutputDir "$archiveName.manifest.txt"
    $hashPath = Join-Path $OutputDir "$archiveName.sha256.txt"

    foreach ($path in @($zipPath, $manifestPath, $hashPath)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }

    Write-Step "Source : $source"
    Write-Step "Branche : $branch"
    Write-Step "Commit : $headCommit"
    Write-Step "Archive : $zipPath"

    Write-Step 'Creation du ZIP depuis HEAD avec core.autocrlf=false'

    Invoke-Git `
        -RepositoryPath $source `
        -Arguments @(
            '-c',
            'core.autocrlf=false',
            'archive',
            '--format=zip',
            "--prefix=$rootPrefix",
            "--output=$zipPath",
            'HEAD'
        ) | Out-Null

    if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
        throw "Archive non creee : $zipPath"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipArchive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)

    $entries = @($zipArchive.Entries)

    if ($entries.Count -eq 0) {
        throw 'Archive invalide : aucune entree ZIP.'
    }

    Write-Step 'Verification de la portabilite des entrees ZIP'

    $entryNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    foreach ($entry in $entries) {
        Assert-PortableEntryPath `
            -EntryPath $entry.FullName `
            -RootPrefix $rootPrefix

        if (-not $entryNames.Add($entry.FullName)) {
            throw "Entree ZIP dupliquee sans distinction de casse : $($entry.FullName)"
        }
    }

    Write-Ok "Entrees portables et uniques : $($entries.Count)"

    $trackedFiles = @(
        (
            Invoke-Git `
                -RepositoryPath $source `
                -Arguments @('ls-tree', '-r', '--name-only', 'HEAD')
        ) -split "\r?\n" |
        Where-Object { $_ -ne '' } |
        Sort-Object
    )

    $archivedFiles = @(
        $entries |
        Where-Object { -not $_.FullName.EndsWith('/') } |
        ForEach-Object {
            $_.FullName.Substring($rootPrefix.Length)
        } |
        Sort-Object
    )

    $pathDifferences = @(
        Compare-Object `
            -ReferenceObject $trackedFiles `
            -DifferenceObject $archivedFiles
    )

    if ($pathDifferences.Count -gt 0) {
        $details = @(
            $pathDifferences |
            ForEach-Object {
                "$($_.SideIndicator) $($_.InputObject)"
            }
        )

        throw (
            'Le contenu du ZIP ne correspond pas exactement aux fichiers suivis par HEAD :' +
            "`n" +
            ($details -join [Environment]::NewLine)
        )
    }

    Write-Ok "Fichiers archives conformes a HEAD : $($archivedFiles.Count)"

    Write-Step 'Verification des chemins sensibles et artefacts interdits'

    $forbiddenPaths = @(
        $archivedFiles |
        Where-Object {
            Test-ForbiddenTrackedPath -RelativePath $_
        }
    )

    if ($forbiddenPaths.Count -gt 0) {
        throw (
            'Archive invalide : chemin(s) interdit(s) suivi(s) par Git :' +
            "`n" +
            ($forbiddenPaths -join [Environment]::NewLine)
        )
    }

    Write-Ok 'Absence de .git, .env reels, secrets, backups, dumps, target et dist'

    Write-Step 'Verification des migrations Flyway'

    $trackedMigrations = @(
        $trackedFiles |
        Where-Object {
            $_ -match '^backend/src/main/resources/db/migration/V.+\.sql$'
        } |
        Sort-Object
    )

    $archivedMigrations = @(
        $archivedFiles |
        Where-Object {
            $_ -match '^backend/src/main/resources/db/migration/V.+\.sql$'
        } |
        Sort-Object
    )

    if ($trackedMigrations.Count -lt 9) {
        throw (
            "Nombre de migrations Flyway insuffisant dans HEAD : " +
            "$($trackedMigrations.Count), minimum attendu : 9."
        )
    }

    $migrationDifferences = @(
        Compare-Object `
            -ReferenceObject $trackedMigrations `
            -DifferenceObject $archivedMigrations
    )

    if ($migrationDifferences.Count -gt 0) {
        throw 'Les migrations Flyway de HEAD ne sont pas toutes presentes dans le ZIP.'
    }

    Write-Ok "Migrations Flyway conservees : $($archivedMigrations.Count)"

    Write-Step 'Comparaison des fichiers temoins avec les blobs Git'

    $latestMigration = $trackedMigrations |
        Sort-Object {
            if ($_ -match '/V(\d+)__') {
                return [int]$Matches[1]
            }

            return 0
        } |
        Select-Object -Last 1

    $witnessPaths = @(
        'README.md',
        'backend/pom.xml',
        'backend/src/main/resources/application.yml',
        'scripts/make-reprise-archive.ps1',
        $latestMigration
    ) |
        Select-Object -Unique

    $entryByRelativePath = @{}

    foreach ($entry in $entries) {
        if (-not $entry.FullName.EndsWith('/')) {
            $relativePath = $entry.FullName.Substring($rootPrefix.Length)
            $entryByRelativePath[$relativePath] = $entry
        }
    }

    foreach ($witnessPath in $witnessPaths) {
        if (-not $entryByRelativePath.ContainsKey($witnessPath)) {
            throw "Fichier temoin absent du ZIP : $witnessPath"
        }

        $blobPath = Join-Path `
            $tempRoot `
            ([guid]::NewGuid().ToString('N') + '.blob')

        Export-GitBlob `
            -RepositoryPath $source `
            -ObjectSpec "HEAD:$witnessPath" `
            -DestinationPath $blobPath

        $blobBytes = [System.IO.File]::ReadAllBytes($blobPath)
        $archiveBytes = Get-EntryBytes -Entry $entryByRelativePath[$witnessPath]

        $blobHash = Get-Sha256Hex -Bytes $blobBytes
        $archiveHash = Get-Sha256Hex -Bytes $archiveBytes

        if ($archiveHash -ne $blobHash) {
            throw (
                "Le fichier archive ne correspond pas au blob Git : $witnessPath" +
                "`nSHA256 blob    : $blobHash" +
                "`nSHA256 archive : $archiveHash" +
                "`nEOL blob       : $(Get-LineEndingSummary -Bytes $blobBytes)" +
                "`nEOL archive    : $(Get-LineEndingSummary -Bytes $archiveBytes)"
            )
        }

        Write-Ok (
            "$witnessPath - SHA256 $archiveHash - " +
            (Get-LineEndingSummary -Bytes $archiveBytes)
        )
    }

    Write-Step 'Generation du manifeste externe'

    $manifestLines = [System.Collections.Generic.List[string]]::new()
    $manifestLines.Add('Subnetory reprise archive manifest')
    $manifestLines.Add('')
    $manifestLines.Add("Archive: $([System.IO.Path]::GetFileName($zipPath))")
    $manifestLines.Add("Git branch: $branch")
    $manifestLines.Add("Git commit: $headCommit")
    $manifestLines.Add("Root prefix: $rootPrefix")
    $manifestLines.Add("Tracked files: $($trackedFiles.Count)")
    $manifestLines.Add("Flyway migrations: $($trackedMigrations.Count)")
    $manifestLines.Add('')
    $manifestLines.Add('TYPE PATH' + "`t" + 'SIZE' + "`t" + 'SHA256')

    foreach ($entry in ($entries | Sort-Object FullName)) {
        if ($entry.FullName.EndsWith('/')) {
            $manifestLines.Add("DIR  $($entry.FullName)")
        }
        else {
            $entryBytes = Get-EntryBytes -Entry $entry
            $entryHash = Get-Sha256Hex -Bytes $entryBytes
            $manifestLines.Add(
                "FILE $($entry.FullName)`t$($entry.Length)`t$entryHash"
            )
        }
    }

    [System.IO.File]::WriteAllText(
        $manifestPath,
        ($manifestLines -join "`r`n") + "`r`n",
        $Utf8NoBom
    )

    $zipArchive.Dispose()
    $zipArchive = $null

    $zipHash = (
        Get-FileHash -LiteralPath $zipPath -Algorithm SHA256
    ).Hash.ToUpperInvariant()

    [System.IO.File]::WriteAllText(
        $hashPath,
        "$zipHash *$([System.IO.Path]::GetFileName($zipPath))`r`n",
        $Utf8NoBom
    )

    $zipInfo = Get-Item -LiteralPath $zipPath

    Write-Host ''
    Write-Host 'Archive de reprise creee et validee avec succes' -ForegroundColor Green
    Write-Host "Version              : $Version"
    Write-Host "Branche              : $branch"
    Write-Host "Commit               : $headCommit"
    Write-Host "Fichiers suivis      : $($trackedFiles.Count)"
    Write-Host "Migrations Flyway    : $($trackedMigrations.Count)"
    Write-Host "Taille ZIP           : $($zipInfo.Length) octets"
    Write-Host "SHA256 ZIP           : $zipHash"
    Write-Host "Chemin ZIP           : $zipPath"
    Write-Host "Manifest externe     : $manifestPath"
    Write-Host "Fichier SHA256       : $hashPath"
    Write-Host ''
}
finally {
    if ($null -ne $zipArchive) {
        $zipArchive.Dispose()
    }

    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item `
            -LiteralPath $tempRoot `
            -Recurse `
            -Force `
            -ErrorAction SilentlyContinue
    }
}
