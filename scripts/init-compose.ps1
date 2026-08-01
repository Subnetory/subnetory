#requires -Version 7.0
<#
.SYNOPSIS
  Initialise les secrets locaux du deploiement Docker Compose de Subnetory.

.DESCRIPTION
  Genere dans backend/secrets les secrets cryptographiquement aleatoires
  demandes, par defaut les 4 :
  - subnetory_jwt_secret
  - subnetory_admin_default_password
  - postgres_password
  - subnetory_backup_encryption_key (chiffrement des sauvegardes au repos,
    backlog #13, voir backend/docs/BACKUP_ENCRYPTION.md ; active par defaut
    depuis docker-compose.yml/docker-compose.prod.yml)

  Aucun secret n'est affiche. Sans -Force, seuls les secrets qui n'existent
  PAS encore sont crees ; tout secret deja present est laisse strictement
  inchange, meme si d'autres secrets sont crees dans le meme appel. C'est ce
  qui permet de relancer ce script sur une instance deja initialisee (par
  exemple pour obtenir le nouveau secret subnetory_backup_encryption_key
  apres une mise a jour) sans toucher au JWT ni au mot de passe PostgreSQL
  existants (donc sans invalider les sessions ni desynchroniser le mot de
  passe du role PostgreSQL avec le volume pgdata deja initialise).

  -WithoutBackupEncryption exclut explicitement
  subnetory_backup_encryption_key de la generation, pour un deploiement qui
  ne veut vraiment pas de cette fonctionnalite (il faut alors aussi
  recommenter/retirer le bloc correspondant dans docker-compose.yml, sinon
  Docker Compose echouera au demarrage faute de fichier secret).

  -WithBackupEncryption reste accepte pour compatibilite ascendante mais est
  desormais un no-op : ce secret est deja genere par defaut.

  -Force regenere tous les secrets demandes par cet appel (rotation
  volontaire) - y compris ceux deja presents.

.EXAMPLE
  # Genere les 4 secrets par defaut, y compris subnetory_backup_encryption_key.
  pwsh.exe -File scripts/init-compose.ps1

.EXAMPLE
  pwsh.exe -File scripts/init-compose.ps1 -Force

.EXAMPLE
  # Exclut explicitement subnetory_backup_encryption_key (chiffrement des
  # sauvegardes desactive volontairement). Fonctionne aussi bien sur une
  # premiere installation que sur une instance existante.
  pwsh.exe -File scripts/init-compose.ps1 -WithoutBackupEncryption

.EXAMPLE
  # Rotation volontaire complete de tous les secrets de base (deconnecte tous
  # les utilisateurs et necessite de reinitialiser le volume PostgreSQL,
  # sinon le mot de passe du role PostgreSQL et le secret se desynchronisent).
  pwsh.exe -File scripts/init-compose.ps1 -Force
#>

[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$WithBackupEncryption,
    [switch]$WithoutBackupEncryption
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
function New-RandomHexSecret {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 4096)]
        [int]$ByteCount
    )

    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-RandomCharacter {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$Characters
    )

    $index = [System.Security.Cryptography.RandomNumberGenerator]::GetInt32($Characters.Length)
    return $Characters[$index]
}

function New-TemporaryAdminPassword {
    $upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
    $lower = 'abcdefghijkmnopqrstuvwxyz'
    $digits = '23456789'
    $special = '!@#$%^&*()-_=+'
    $combined = $upper + $lower + $digits + $special

    $characters = [System.Collections.Generic.List[char]]::new()
    $characters.Add((Get-RandomCharacter -Characters $upper))
    $characters.Add((Get-RandomCharacter -Characters $lower))
    $characters.Add((Get-RandomCharacter -Characters $digits))
    $characters.Add((Get-RandomCharacter -Characters $special))

    while ($characters.Count -lt 32) {
        $characters.Add((Get-RandomCharacter -Characters $combined))
    }

    for ($index = $characters.Count - 1; $index -gt 0; $index--) {
        $swapIndex = [System.Security.Cryptography.RandomNumberGenerator]::GetInt32($index + 1)
        $temporary = $characters[$index]
        $characters[$index] = $characters[$swapIndex]
        $characters[$swapIndex] = $temporary
    }

    return -join $characters
}

function Assert-SecretValue {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('jwt', 'admin', 'postgres', 'backup_encryption')]
        [string]$Kind,

        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$Value
    )

    if ($Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "Le secret $Kind contient une fin de ligne interdite."
    }

    switch ($Kind) {
        'jwt' {
            if ($Value.Length -lt 128 -or $Value -notmatch '^[0-9a-f]+$') {
                throw 'Le secret JWT doit representer au moins 64 octets aleatoires en hexadecimal.'
            }
        }
        'postgres' {
            if ($Value.Length -lt 64 -or $Value -notmatch '^[0-9a-f]+$') {
                throw 'Le mot de passe PostgreSQL doit representer au moins 32 octets aleatoires en hexadecimal.'
            }
        }
        'backup_encryption' {
            if ($Value.Length -lt 64 -or $Value -notmatch '^[0-9a-f]+$') {
                throw 'La passphrase de chiffrement des sauvegardes doit representer au moins 32 octets aleatoires en hexadecimal.'
            }
        }
        'admin' {
            if (
                $Value.Length -lt 24 -or
                $Value.Length -gt 128 -or
                $Value -notmatch '[A-Z]' -or
                $Value -notmatch '[a-z]' -or
                $Value -notmatch '[0-9]' -or
                $Value -notmatch '[^A-Za-z0-9]'
            ) {
                throw 'Le mot de passe administrateur temporaire ne respecte pas la politique Subnetory.'
            }
        }
    }
}

function Set-RestrictivePermissions {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DirectoryPath,

        [Parameter(Mandatory = $true)]
        [string[]]$FilePaths
    )

    if ($IsWindows) {
        $icacls = Get-Command 'icacls.exe' -ErrorAction SilentlyContinue

        if ($null -eq $icacls) {
            Write-Warning 'icacls.exe absent : permissions Windows restrictives non appliquees.'
            return
        }

        $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $directoryGrants = @(
            ('*{0}:(OI)(CI)F' -f $currentSid),
            '*S-1-5-18:(OI)(CI)F',
            '*S-1-5-32-544:(OI)(CI)F'
        )

        & $icacls.Source $DirectoryPath '/inheritance:r' '/grant:r' @directoryGrants | Out-Null

        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de securiser le repertoire $DirectoryPath avec icacls."
        }

        $fileGrants = @(
            ('*{0}:F' -f $currentSid),
            '*S-1-5-18:F',
            '*S-1-5-32-544:F'
        )

        foreach ($filePath in $FilePaths) {
            & $icacls.Source $filePath '/inheritance:r' '/grant:r' @fileGrants | Out-Null

            if ($LASTEXITCODE -ne 0) {
                throw "Impossible de securiser le fichier $filePath avec icacls."
            }
        }

        return
    }

    $directoryMode =
        [System.IO.UnixFileMode]::UserRead -bor
        [System.IO.UnixFileMode]::UserWrite -bor
        [System.IO.UnixFileMode]::UserExecute

    $fileMode =
        [System.IO.UnixFileMode]::UserRead -bor
        [System.IO.UnixFileMode]::UserWrite

    [System.IO.File]::SetUnixFileMode($DirectoryPath, $directoryMode)

    foreach ($filePath in $FilePaths) {
        [System.IO.File]::SetUnixFileMode($filePath, $fileMode)
    }
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$sourceRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$backendRoot = Join-Path $sourceRoot 'backend'
$secretsRoot = Join-Path $backendRoot 'secrets'

if (-not (Test-Path -LiteralPath $backendRoot -PathType Container)) {
    throw "Repertoire backend introuvable : $backendRoot"
}

$secretDefinitions = [ordered]@{
    'subnetory_jwt_secret' = 'jwt'
    'subnetory_admin_default_password' = 'admin'
    'postgres_password' = 'postgres'
}

if (-not $WithoutBackupEncryption) {
    $secretDefinitions['subnetory_backup_encryption_key'] = 'backup_encryption'
}

$targetPaths = @(
    $secretDefinitions.Keys |
        ForEach-Object { Join-Path $secretsRoot $_ }
)

$existingNames = @(
    $secretDefinitions.Keys |
        Where-Object { Test-Path -LiteralPath (Join-Path $secretsRoot $_) }
)

# Sans -Force : on ne (re)cree que les secrets demandes qui n'existent pas
# encore. Un secret deja present reste strictement inchange, meme si
# d'autres secrets sont crees dans le meme appel (ex. relancer ce script sans
# -WithoutBackupEncryption sur une instance qui a deja ses 3 secrets de
# base : seul subnetory_backup_encryption_key sera cree). Avec -Force, tous
# les secrets demandes sont regeneres (rotation volontaire explicite).
$namesToWrite = @(
    if ($Force) {
        $secretDefinitions.Keys
    }
    else {
        $secretDefinitions.Keys | Where-Object { $existingNames -notcontains $_ }
    }
)

$namesLeftUntouched = @(
    $secretDefinitions.Keys | Where-Object { $namesToWrite -notcontains $_ }
)

if ($namesToWrite.Count -eq 0) {
    $relativeExisting = @(
        $existingNames |
            ForEach-Object { [System.IO.Path]::GetRelativePath($sourceRoot, (Join-Path $secretsRoot $_)) }
    )

    throw (
        'Tous les secrets demandes existent deja. Aucun fichier n''a ete modifie.' +
        "`nUtiliser -Force uniquement pour une rotation volontaire." +
        "`nFichiers existants :" +
        "`n" +
        ($relativeExisting -join [Environment]::NewLine)
    )
}

New-Item -ItemType Directory -Path $secretsRoot -Force | Out-Null

$stagingRoot = Join-Path $secretsRoot ('.staging-' + [guid]::NewGuid().ToString('N'))
$backupRoot = Join-Path $secretsRoot ('.backup-' + [guid]::NewGuid().ToString('N'))
$installedNames = [System.Collections.Generic.List[string]]::new()
$backedUpNames = [System.Collections.Generic.List[string]]::new()
$completed = $false

try {
    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

    $values = [ordered]@{}
    foreach ($name in $namesToWrite) {
        $values[$name] = switch ($name) {
            'subnetory_jwt_secret' { New-RandomHexSecret -ByteCount 64 }
            'subnetory_admin_default_password' { New-TemporaryAdminPassword }
            'postgres_password' { New-RandomHexSecret -ByteCount 32 }
            'subnetory_backup_encryption_key' { New-RandomHexSecret -ByteCount 32 }
        }
    }

    foreach ($name in $namesToWrite) {
        $value = $values[$name]
        Assert-SecretValue -Kind $secretDefinitions[$name] -Value $value

        $stagedPath = Join-Path $stagingRoot $name
        [System.IO.File]::WriteAllText($stagedPath, $value, $Utf8NoBom)

        $bytes = [System.IO.File]::ReadAllBytes($stagedPath)

        if (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        ) {
            throw "BOM UTF-8 interdit dans $name."
        }
    }

    if ($values.Values.Count -ne (@($values.Values | Select-Object -Unique)).Count) {
        throw 'Les secrets generes doivent tous etre distincts.'
    }

    foreach ($name in $namesToWrite) {
        $targetPath = Join-Path $secretsRoot $name

        if (Test-Path -LiteralPath $targetPath) {
            Move-Item -LiteralPath $targetPath -Destination (Join-Path $backupRoot $name)
            $backedUpNames.Add($name)
        }
    }

    foreach ($name in $namesToWrite) {
        Move-Item -LiteralPath (Join-Path $stagingRoot $name) -Destination (Join-Path $secretsRoot $name)
        $installedNames.Add($name)
    }

    Set-RestrictivePermissions -DirectoryPath $secretsRoot -FilePaths $targetPaths
    $completed = $true
}
catch {
    foreach ($name in $installedNames) {
        $installedPath = Join-Path $secretsRoot $name

        if (Test-Path -LiteralPath $installedPath) {
            Remove-Item -LiteralPath $installedPath -Force
        }
    }

    foreach ($name in $backedUpNames) {
        $backupPath = Join-Path $backupRoot $name

        if (Test-Path -LiteralPath $backupPath) {
            Move-Item -LiteralPath $backupPath -Destination (Join-Path $secretsRoot $name) -Force
        }
    }

    throw
}
finally {
    foreach ($path in @($stagingRoot, $backupRoot)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }
}

if (-not $completed) {
    throw 'Initialisation des secrets interrompue.'
}

$secretLabels = [ordered]@{
    'subnetory_jwt_secret' = 'Secret JWT'
    'subnetory_admin_default_password' = 'Mot de passe admin temporaire'
    'postgres_password' = 'Mot de passe DB'
    'subnetory_backup_encryption_key' = 'Cle de chiffrement des sauvegardes'
}

Write-Host 'Secrets Docker Compose initialises.' -ForegroundColor Green
Write-Host "Repertoire       : $secretsRoot"

foreach ($name in $secretDefinitions.Keys) {
    $label = $secretLabels[$name]

    if ($namesToWrite -contains $name) {
        Write-Host "$label : cree, valeur non affichee"
    }
    else {
        Write-Host "$label : deja present, non modifie"
    }
}

if ($namesLeftUntouched.Count -gt 0) {
    Write-Host ''
    Write-Host 'Secrets deja existants, laisses strictement inchanges (-Force pour une rotation volontaire) :'
    foreach ($name in $namesLeftUntouched) {
        Write-Host "  - $name"
    }
}

Write-Host 'Pour lire le mot de passe admin temporaire :'
Write-Host "  Get-Content -LiteralPath `"$(Join-Path $secretsRoot 'subnetory_admin_default_password')`" -Raw"
