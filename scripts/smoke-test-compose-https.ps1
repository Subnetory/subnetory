#requires -Version 7.0
<#
    Smoke test leger de l'overlay HTTPS (Sprint 2.36).

    Valide, sur le commit HEAD exact du depot (via git archive, comme
    scripts/smoke-test-compose.ps1) :
      - la configuration Compose base + overlay est valide et ne fuite aucun
        secret genere ;
      - la stack demarre avec Caddy en mode "reseau interne" (tls internal) ;
      - l'endpoint de sante readiness repond en HTTPS a travers Caddy ;
      - le service app n'est plus publie directement sur l'hote (seul Caddy
        publie un port) ;
      - aucun secret genere n'apparait dans les logs Compose.

    Ce script complete scripts/smoke-test-compose.ps1 (deja tres complet sur
    le parcours JWT/administrateur en HTTP direct) : il ne reteste pas ce
    parcours, seulement la delta introduite par l'overlay HTTPS.
#>
[CmdletBinding()]
param(
    [string]$RepositoryPath,
    [string]$RedactedLogPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:SecretValues = @()
$script:ComposeStarted = $false

function Protect-Text {
    param([AllowNull()][string]$Text)

    if ($null -eq $Text) {
        return ""
    }

    $Protected = $Text
    foreach ($Secret in $script:SecretValues) {
        if (-not [string]::IsNullOrEmpty($Secret)) {
            $Protected = $Protected.Replace($Secret, "[REDACTED]")
        }
    }

    return $Protected
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$WorkingDirectory,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 600
    )

    $StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $StartInfo.FileName = $FileName
    $StartInfo.UseShellExecute = $false
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    $StartInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $StartInfo.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
    $StartInfo.CreateNoWindow = $true

    if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        $StartInfo.WorkingDirectory = $WorkingDirectory
    }

    foreach ($Argument in $Arguments) {
        $StartInfo.ArgumentList.Add($Argument)
    }

    $Process = [System.Diagnostics.Process]::new()
    $Process.StartInfo = $StartInfo

    if (-not $Process.Start()) {
        throw "Impossible de demarrer $FileName."
    }

    $StdoutTask = $Process.StandardOutput.ReadToEndAsync()
    $StderrTask = $Process.StandardError.ReadToEndAsync()

    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $Process.Kill($true) } catch { }
        throw "Delai depasse : $FileName $($Arguments -join ' ') (timeout ${TimeoutSeconds}s)"
    }

    $Stdout = $StdoutTask.GetAwaiter().GetResult()
    $Stderr = $StderrTask.GetAwaiter().GetResult()

    if (-not $AllowFailure -and $Process.ExitCode -ne 0) {
        throw (
            "Echec : $FileName $($Arguments -join ' ')" +
            "`nCode retour : $($Process.ExitCode)" +
            "`nSTDOUT :`n$(Protect-Text $Stdout)" +
            "`nSTDERR :`n$(Protect-Text $Stderr)"
        )
    }

    [PSCustomObject]@{
        ExitCode = $Process.ExitCode
        Stdout   = $Stdout.TrimEnd()
        Stderr   = $Stderr.TrimEnd()
    }
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 600
    )

    return Invoke-Native `
        -FileName "docker" `
        -Arguments (@(
            "compose", "-p", $ProjectName,
            "-f", "docker-compose.yml",
            "-f", "docker-compose.https.yml"
        ) + $Arguments) `
        -WorkingDirectory $BackendPath `
        -AllowFailure:$AllowFailure `
        -TimeoutSeconds $TimeoutSeconds
}

function New-RandomBase64Url {
    param([int]$ByteCount)
    $Bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-FreeTcpPort {
    $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $Listener.Start()
        return ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port
    }
    finally {
        $Listener.Stop()
    }
}

function Write-Utf8NoBomNoNewline {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Value
    )
    [System.IO.File]::WriteAllText($Path, $Value, [System.Text.UTF8Encoding]::new($false))
}

function Assert-NoSecretValuesInText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )
    foreach ($Value in $script:SecretValues) {
        if (-not [string]::IsNullOrEmpty($Value) -and $Text.Contains($Value)) {
            throw "Valeur secrete detectee dans $Source."
        }
    }
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = (
        Invoke-Native -FileName "git" -Arguments @(
            "-C", (Join-Path $PSScriptRoot ".."), "rev-parse", "--show-toplevel"
        )
    ).Stdout.Trim()
}

$RepositoryPath = [System.IO.Path]::GetFullPath($RepositoryPath)
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("subnetory-https-smoke-" + [guid]::NewGuid().ToString("N"))
$ArchivePath = Join-Path $TempRoot "backend-source.zip"
$SourceRoot = Join-Path $TempRoot "source"
$BackendPath = Join-Path $SourceRoot "backend"
$ProjectName = ("subnetory-https-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12)).ToLowerInvariant()

$HttpsPort = Get-FreeTcpPort
$HttpPort = Get-FreeTcpPort

# Caddy route par SNI/Host : le Caddyfile.internal declare le site
# "subnetory.local", pas un bloc catch-all. Une connexion vers 127.0.0.1 sans
# ce nom ne correspond a aucun site et le handshake TLS echoue. On resout
# donc ce nom vers la boucle locale via le fichier hosts, comme le ferait un
# poste client reel (voir HTTPS_REVERSE_PROXY.md).
$script:SmokeHostName = "subnetory.local"
$script:HostsEntryAdded = $false
$script:HostsFilePath = if ($IsWindows) {
    Join-Path $env:WINDIR "System32\drivers\etc\hosts"
}
else {
    "/etc/hosts"
}

$JwtSecret = New-RandomBase64Url -ByteCount 64
$PostgresPassword = New-RandomBase64Url -ByteCount 36
$AdminPassword = New-RandomBase64Url -ByteCount 24
$BackupEncryptionKey = New-RandomBase64Url -ByteCount 36
$script:SecretValues = @($JwtSecret, $PostgresPassword, $AdminPassword, $BackupEncryptionKey)

Write-Host "=== Smoke test overlay HTTPS (Caddy, mode interne) ===" -ForegroundColor Cyan
Write-Host "Projet Compose : $ProjectName"
Write-Host "Port HTTPS     : $HttpsPort"
Write-Host "Port HTTP      : $HttpPort"

try {
    try {
        $null = Get-Command git -ErrorAction Stop
        $null = Get-Command docker -ErrorAction Stop

        if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) {
            throw "Depot introuvable : $RepositoryPath"
        }

        $Status = Invoke-Native -FileName "git" -Arguments @(
            "-C", $RepositoryPath, "status", "--porcelain=v1", "--untracked-files=all"
        )
        if (-not [string]::IsNullOrWhiteSpace($Status.Stdout)) {
            throw "Le working tree doit etre propre avant le smoke test."
        }

        New-Item -ItemType Directory -Path $SourceRoot -Force | Out-Null

        $null = Invoke-Native -FileName "git" -Arguments @(
            "-C", $RepositoryPath, "-c", "core.autocrlf=false",
            "archive", "--format=zip", "--output=$ArchivePath", "HEAD", "backend"
        ) -WorkingDirectory $RepositoryPath

        Expand-Archive -LiteralPath $ArchivePath -DestinationPath $SourceRoot -Force

        if (-not (Test-Path -LiteralPath $BackendPath -PathType Container)) {
            throw "Le repertoire backend est absent de l'archive Git."
        }

        $SecretsPath = Join-Path $BackendPath "secrets"
        New-Item -ItemType Directory -Path $SecretsPath -Force | Out-Null
        Write-Utf8NoBomNoNewline -Path (Join-Path $SecretsPath "subnetory_jwt_secret") -Value $JwtSecret
        Write-Utf8NoBomNoNewline -Path (Join-Path $SecretsPath "subnetory_admin_default_password") -Value $AdminPassword
        Write-Utf8NoBomNoNewline -Path (Join-Path $SecretsPath "postgres_password") -Value $PostgresPassword
        Write-Utf8NoBomNoNewline -Path (Join-Path $SecretsPath "subnetory_backup_encryption_key") -Value $BackupEncryptionKey

        $DeployPath = Join-Path $BackendPath "deploy"
        Copy-Item -LiteralPath (Join-Path $DeployPath "Caddyfile.internal") -Destination (Join-Path $DeployPath "Caddyfile") -Force

        Write-Host "`n=== Validation de la configuration ===" -ForegroundColor Cyan

        $ConfigResult = Invoke-Compose -Arguments @("config")
        Assert-NoSecretValuesInText -Text ($ConfigResult.Stdout + "`n" + $ConfigResult.Stderr) -Source "docker compose config"
        Write-Host "Configuration    : valide, aucun secret rendu"

        Write-Host "`n=== Demarrage (build + overlay HTTPS) ===" -ForegroundColor Cyan

        [Environment]::SetEnvironmentVariable("HTTPS_PORT", [string]$HttpsPort, "Process")
        [Environment]::SetEnvironmentVariable("HTTP_PORT", [string]$HttpPort, "Process")

        $null = Invoke-Compose -Arguments @("up", "-d", "--build") -TimeoutSeconds 1800
        $script:ComposeStarted = $true

        Write-Host "`n=== Resolution locale du nom de site Caddy ===" -ForegroundColor Cyan

        $HostsContent = Get-Content -LiteralPath $script:HostsFilePath -Raw -ErrorAction Stop
        $HostsEntryLine = "127.0.0.1 $script:SmokeHostName"

        if ($HostsContent -notmatch [regex]::Escape($script:SmokeHostName)) {
            if ($IsWindows) {
                Add-Content -LiteralPath $script:HostsFilePath -Value $HostsEntryLine -ErrorAction Stop
            }
            else {
                $null = Invoke-Native -FileName "sudo" -Arguments @(
                    "sh", "-c", "echo '$HostsEntryLine' >> $($script:HostsFilePath)"
                )
            }
            $script:HostsEntryAdded = $true
            Write-Host "Fichier hosts    : entree ajoutee ($HostsEntryLine)"
        }
        else {
            Write-Host "Fichier hosts    : entree deja presente pour $script:SmokeHostName"
        }

        Write-Host "`n=== Verification HTTPS ===" -ForegroundColor Cyan

        $Deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
        $Last = $null
        $Reached = $false

        while ([DateTimeOffset]::UtcNow -lt $Deadline) {
            try {
                $Response = Invoke-WebRequest `
                    -Uri "https://$($script:SmokeHostName):$HttpsPort/actuator/health/readiness" `
                    -SkipCertificateCheck `
                    -TimeoutSec 10 `
                    -SkipHttpErrorCheck

                # Invoke-WebRequest peut retourner .Content en byte[] plutot
                # qu'en string selon l'en-tete Content-Type recu (Caddy ne
                # renvoie pas toujours un type reconnu comme texte pendant le
                # demarrage). Normaliser explicitement en string avant tout
                # traitement, sans supposer le type runtime.
                $ContentText = if ($Response.Content -is [byte[]]) {
                    [System.Text.Encoding]::UTF8.GetString([byte[]]$Response.Content)
                }
                else {
                    [string]$Response.Content
                }

                # Acces via PSObject.Properties plutot que la notation pointee
                # directe : sous Set-StrictMode -Version Latest, une reponse
                # non-JSON (ex. page d'erreur Caddy pendant le demarrage)
                # ferait echouer le parsing ET l'acces a une propriete
                # absente, avec le meme message d'erreur pour les deux cas.
                $ParsedStatus = $null
                if ($Response.StatusCode -eq 200) {
                    try {
                        $BodyObject = $ContentText | ConvertFrom-Json -ErrorAction Stop
                        $StatusProperty = $BodyObject.PSObject.Properties["status"]
                        if ($null -ne $StatusProperty) {
                            $ParsedStatus = [string]$StatusProperty.Value
                        }
                    }
                    catch {
                        $ParsedStatus = $null
                    }
                }

                $ContentSnippet = if ([string]::IsNullOrEmpty($ContentText)) {
                    "(corps vide)"
                }
                else {
                    $ContentText.Substring(0, [Math]::Min(300, $ContentText.Length))
                }
                $Last = "HTTP $($Response.StatusCode), status analyse=$ParsedStatus, corps=$ContentSnippet"

                if ($ParsedStatus -eq "UP") {
                    $Reached = $true
                    break
                }
            }
            catch {
                $Last = $_.Exception.Message
            }
            Start-Sleep -Seconds 3
        }

        if (-not $Reached) {
            throw "Readiness HTTPS non atteint via Caddy apres 180s. Dernier resultat : $Last"
        }

        Write-Host "Readiness HTTPS  : UP (via Caddy, certificat auto-signe)"

        Write-Host "`n=== Verification absence d'exposition directe de l'app ===" -ForegroundColor Cyan

        $AppPortResult = Invoke-Compose -Arguments @("port", "app", "8080") -AllowFailure -TimeoutSeconds 60
        $AppPortOutput = $AppPortResult.Stdout.Trim()

        if ($AppPortResult.ExitCode -eq 0 -and $AppPortOutput -match ':(\d{1,5})$' -and [int]$Matches[1] -ge 1) {
            throw "Le service app est publie directement sur l'hote : $AppPortOutput"
        }

        Write-Host "Exposition app   : aucun port hote publie directement (seul Caddy)"

        Write-Host "`n=== Verification absence de secrets dans les logs ===" -ForegroundColor Cyan

        $LogsResult = Invoke-Compose -Arguments @("logs", "--no-color") -AllowFailure -TimeoutSeconds 120
        Assert-NoSecretValuesInText -Text ($LogsResult.Stdout + "`n" + $LogsResult.Stderr) -Source "logs Compose"

        Write-Host "Logs Compose     : aucune valeur secrete detectee"
        Write-Host "`n=== Smoke test HTTPS reussi ===" -ForegroundColor Green
    }
    catch {
        if (-not [string]::IsNullOrWhiteSpace($RedactedLogPath)) {
            $Diagnostics = "Echec du smoke test HTTPS : $($_.Exception.Message)"
            if ($script:ComposeStarted) {
                $LogsOnFailure = Invoke-Compose -Arguments @("logs", "--no-color", "--tail", "300") -AllowFailure -TimeoutSeconds 120
                $Diagnostics += "`n`n=== logs Compose (300 dernieres lignes, expurges) ===`n" +
                    (Protect-Text ($LogsOnFailure.Stdout + "`n" + $LogsOnFailure.Stderr))
            }
            [System.IO.File]::WriteAllText($RedactedLogPath, $Diagnostics, [System.Text.UTF8Encoding]::new($false))
        }
        throw
    }
}
finally {
    if ($script:ComposeStarted) {
        try {
            $null = Invoke-Compose -Arguments @("down", "-v", "--remove-orphans") -AllowFailure -TimeoutSeconds 300
        }
        catch {
            Write-Warning "Nettoyage Compose incomplet : $($_.Exception.Message)"
        }
    }

    if ($script:HostsEntryAdded) {
        try {
            if ($IsWindows) {
                $Filtered = @(
                    Get-Content -LiteralPath $script:HostsFilePath |
                    Where-Object { $_ -notmatch [regex]::Escape($script:SmokeHostName) }
                )
                Set-Content -LiteralPath $script:HostsFilePath -Value $Filtered -ErrorAction Stop
            }
            else {
                $null = Invoke-Native -FileName "sudo" -Arguments @(
                    "sed", "-i", "/$($script:SmokeHostName)/d", $script:HostsFilePath
                ) -AllowFailure
            }
        }
        catch {
            Write-Warning "Nettoyage de l'entree hosts incomplet : $($_.Exception.Message)"
        }
    }

    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    [Environment]::SetEnvironmentVariable("HTTPS_PORT", $null, "Process")
    [Environment]::SetEnvironmentVariable("HTTP_PORT", $null, "Process")
}
