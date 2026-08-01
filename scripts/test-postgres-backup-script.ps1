#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$RepositoryPath,
    [string]$DockerPath = "docker",
    [string]$PostgresImage = "postgres:17.10-alpine3.23"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$script:SecretValues = [System.Collections.Generic.List[string]]::new()
$script:NetworkName = ""
$script:ServerName = ""
$script:BackupRoot = ""
$script:BackupScriptPath = ""
$script:PostgresImage = $PostgresImage
$script:DockerPath = $DockerPath

function Protect-Text {
    param([AllowNull()][string]$Text)

    if ($null -eq $Text) {
        return ""
    }

    $protected = $Text
    foreach ($secret in $script:SecretValues) {
        if (-not [string]::IsNullOrWhiteSpace($secret)) {
            $protected = $protected.Replace($secret, "[REDACTED]")
        }
    }
    return $protected
}

function Assert-NoSecret {
    param([AllowNull()][string]$Text)

    if ($null -eq $Text) {
        return
    }
    foreach ($secret in $script:SecretValues) {
        if (-not [string]::IsNullOrWhiteSpace($secret) -and $Text.Contains($secret)) {
            throw "Une valeur secrète apparaît dans les journaux du test."
        }
    }
}

function Invoke-BackupContainer {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("hourly", "daily", "monthly", "quarterly")]
        [string]$Level,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 36500)]
        [int]$RetentionDays,
        [Parameter(Mandatory = $true)]
        [string]$PasswordFile,
        [Parameter(Mandatory = $true)]
        [bool]$ShouldSucceed
    )

    $output = (& $script:DockerPath run `
        --rm `
        --network $script:NetworkName `
        --user "70:70" `
        --read-only `
        --tmpfs "/tmp:rw,noexec,nosuid,nodev,size=16m" `
        --cap-drop ALL `
        --security-opt no-new-privileges `
        --volume "${script:BackupRoot}:/backups" `
        --volume "${script:BackupScriptPath}:/opt/subnetory-backup/backup.sh:ro" `
        --volume "${PasswordFile}:/run/secrets/postgres-password:ro" `
        --env "BACKUP_LEVEL=$Level" `
        --env "RETENTION_DAYS=$RetentionDays" `
        --env "BACKUP_ROOT=/backups" `
        --env "PGHOST=$($script:ServerName)" `
        --env "PGPORT=5432" `
        --env "PGDATABASE=subnetory" `
        --env "PGUSER=subnetory" `
        --env "POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password" `
        --entrypoint /bin/sh `
        $script:PostgresImage `
        /opt/subnetory-backup/backup.sh 2>&1) -join "`n"
    $exitCode = $LASTEXITCODE
    Assert-NoSecret -Text $output

    if ($ShouldSucceed -and $exitCode -ne 0) {
        throw "La sauvegarde $Level a échoué : $(Protect-Text $output)"
    }
    if (-not $ShouldSucceed -and $exitCode -eq 0) {
        throw "La sauvegarde $Level devait échouer."
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Log = Protect-Text $output
    }
}

function Grant-TestRootAccess {
    if ($IsWindows -or [string]::IsNullOrWhiteSpace($script:BackupRoot)) {
        return
    }
    if (-not (Test-Path -LiteralPath $script:BackupRoot)) {
        return
    }
    & $script:DockerPath run `
        --rm `
        --user "0:0" `
        --volume "${script:BackupRoot}:/cleanup" `
        --entrypoint /bin/sh `
        $script:PostgresImage `
        -c "chmod -R 0777 /cleanup" *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Préparation des droits des artefacts de sauvegarde impossible."
    }
}

function New-ExpiredArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    if ($IsWindows) {
        [System.IO.File]::WriteAllText($Path, $Content)
        (Get-Item -LiteralPath $Path).LastWriteTimeUtc = (
            [DateTime]::SpecifyKind(
                [DateTime]"2000-01-01T00:00:00",
                [DateTimeKind]::Utc
            )
        )
        return
    }

    $relativePath = [System.IO.Path]::GetRelativePath($script:BackupRoot, $Path)
    if ($relativePath.StartsWith("..", [System.StringComparison]::Ordinal)) {
        throw "Refus de créer un artefact hors du dossier de sauvegarde."
    }
    $containerPath = "/cleanup/" + $relativePath.Replace("\", "/")
    & $script:DockerPath run `
        --rm `
        --user "0:0" `
        --volume "${script:BackupRoot}:/cleanup" `
        --env "TARGET=$containerPath" `
        --env "CONTENT=$Content" `
        --entrypoint /bin/sh `
        $script:PostgresImage `
        -c 'mkdir -p "$(dirname "$TARGET")" && printf "%s" "$CONTENT" > "$TARGET" && touch -t 200001010000 "$TARGET" && chmod 0777 "$TARGET"' *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Création de l'artefact expiré de test impossible."
    }
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}
else {
    $RepositoryPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
}

$script:BackupScriptPath = (
    Resolve-Path (Join-Path $RepositoryPath "charts\subnetory\files\backup.sh")
).Path

$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$script:NetworkName = "subnetory-backup-$runId"
$script:ServerName = "subnetory-postgres-$runId"
$testRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) "subnetory-backup-script-test-$runId"
$script:BackupRoot = Join-Path $testRoot "backups"
$secretRoot = Join-Path $testRoot "secrets"
$passwordFile = Join-Path $secretRoot "postgres-password"
$wrongPasswordFile = Join-Path $secretRoot "wrong-password"
$postgresPassword = "S2_32:$runId\Pg"
$wrongPassword = "wrong-$runId"
$script:SecretValues.Add($postgresPassword)
$script:SecretValues.Add($wrongPassword)
$networkCreated = $false
$serverStarted = $false

try {
    & $DockerPath version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker est indisponible."
    }
    & $DockerPath image inspect $PostgresImage *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "L'image PostgreSQL épinglée est absente : $PostgresImage"
    }

    New-Item -ItemType Directory -Path $script:BackupRoot, $secretRoot -Force |
        Out-Null
    if (-not $IsWindows) {
        & chmod 0777 $script:BackupRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Préparation des droits du dossier de sauvegarde impossible."
        }
    }
    [System.IO.File]::WriteAllText(
        $passwordFile,
        $postgresPassword,
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        $wrongPasswordFile,
        $wrongPassword,
        [System.Text.UTF8Encoding]::new($false)
    )

    & $DockerPath network create $script:NetworkName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Création du réseau Docker impossible."
    }
    $networkCreated = $true

    $serverId = (& $DockerPath run `
        --detach `
        --rm `
        --name $script:ServerName `
        --network $script:NetworkName `
        --volume "${passwordFile}:/run/secrets/postgres-password:ro" `
        --env "POSTGRES_DB=subnetory" `
        --env "POSTGRES_USER=subnetory" `
        --env "POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password" `
        $PostgresImage) -join ""
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverId)) {
        throw "Démarrage du PostgreSQL temporaire impossible."
    }
    $serverStarted = $true

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    do {
        & $DockerPath exec $script:ServerName `
            pg_isready `
            --host=127.0.0.1 `
            --username=subnetory `
            --dbname=subnetory *> $null
        $ready = $LASTEXITCODE -eq 0
        if (-not $ready) {
            Start-Sleep -Seconds 1
        }
    } while (-not $ready -and [DateTimeOffset]::UtcNow -lt $deadline)
    if (-not $ready) {
        throw "PostgreSQL temporaire non prêt."
    }

    & $DockerPath exec $script:ServerName `
        psql `
        --host=127.0.0.1 `
        --username=subnetory `
        --dbname=subnetory `
        --set ON_ERROR_STOP=1 `
        --command (
            "CREATE TABLE backup_probe " +
            "(id integer PRIMARY KEY, marker text NOT NULL); " +
            "INSERT INTO backup_probe VALUES (232, 'sprint-2.32');"
        ) | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Création de la donnée témoin impossible."
    }

    $null = Invoke-BackupContainer `
        -Level daily `
        -RetentionDays 1 `
        -PasswordFile $passwordFile `
        -ShouldSucceed $true
    Grant-TestRootAccess

    $dailyRoot = Join-Path $script:BackupRoot "daily"
    $dailyDumps = @(Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
        $_.Name.EndsWith(".dump.gz", [System.StringComparison]::Ordinal)
    })
    $dailyHashes = @(
        Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
            $_.Name.EndsWith(".dump.gz.sha256", [System.StringComparison]::Ordinal)
        }
    )
    if (
        $dailyDumps.Count -ne 1 -or
        $dailyHashes.Count -ne 1 -or
        $dailyDumps[0].Length -le 0
    ) {
        throw "Le premier couple dump/SHA-256 est invalide."
    }

    $validationCommand = (
        'set -eu; set -o pipefail; cd /backups/daily; ' +
        'set -- subnetory-daily-*.dump.gz; [ "$#" -eq 1 ]; ' +
        '[ -f "$1" ]; gzip -t "$1"; ' +
        'sha256sum -c "$1.sha256" >/dev/null; ' +
        'gzip -dc "$1" | pg_restore --list | grep -q backup_probe'
    )
    $validationOutput = (& $DockerPath run `
        --rm `
        --user "70:70" `
        --read-only `
        --cap-drop ALL `
        --security-opt no-new-privileges `
        --volume "${script:BackupRoot}:/backups:ro" `
        --entrypoint /bin/sh `
        $PostgresImage `
        -c $validationCommand 2>&1) -join "`n"
    Assert-NoSecret -Text $validationOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Validation externe du dump impossible : $(Protect-Text $validationOutput)"
    }

    $oldDailyDump = Join-Path $dailyRoot "subnetory-daily-20000101T000000Z.dump.gz"
    $oldDailyHash = "$oldDailyDump.sha256"
    New-ExpiredArtifact -Path $oldDailyDump -Content "expired"
    New-ExpiredArtifact -Path $oldDailyHash -Content "expired"

    $hourlyRoot = Join-Path $script:BackupRoot "hourly"
    New-Item -ItemType Directory -Path $hourlyRoot -Force | Out-Null
    $hourlyDump = Join-Path (
        $hourlyRoot
    ) "subnetory-hourly-20000101T000000Z.dump.gz"
    $hourlyHash = "$hourlyDump.sha256"
    New-ExpiredArtifact -Path $hourlyDump -Content "preserved"
    New-ExpiredArtifact -Path $hourlyHash -Content "preserved"

    Start-Sleep -Seconds 2
    $null = Invoke-BackupContainer `
        -Level daily `
        -RetentionDays 1 `
        -PasswordFile $passwordFile `
        -ShouldSucceed $true
    Grant-TestRootAccess

    $dailyDumpsAfter = @(
        Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
            $_.Name.EndsWith(".dump.gz", [System.StringComparison]::Ordinal)
        }
    )
    $dailyHashesAfter = @(
        Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
            $_.Name.EndsWith(".dump.gz.sha256", [System.StringComparison]::Ordinal)
        }
    )
    if (
        (Test-Path -LiteralPath $oldDailyDump) -or
        (Test-Path -LiteralPath $oldDailyHash)
    ) {
        throw "La rotation daily n'a pas supprimé l'ancien couple."
    }
    if (
        -not (Test-Path -LiteralPath $hourlyDump) -or
        -not (Test-Path -LiteralPath $hourlyHash)
    ) {
        throw "La rotation daily a supprimé une sauvegarde hourly."
    }

    $null = Invoke-BackupContainer `
        -Level monthly `
        -RetentionDays 1 `
        -PasswordFile $wrongPasswordFile `
        -ShouldSucceed $false
    Grant-TestRootAccess
    $monthlyRoot = Join-Path $script:BackupRoot "monthly"
    if (Test-Path -LiteralPath $monthlyRoot) {
        $monthlyArtifacts = @(Get-ChildItem -LiteralPath $monthlyRoot -File)
        if ($monthlyArtifacts.Count -ne 0) {
            throw "Un échec d'authentification a laissé un artefact."
        }
    }

    if (-not $IsWindows) {
        $quarterlyRoot = Join-Path $script:BackupRoot "quarterly"
        New-Item -ItemType Directory -Path $quarterlyRoot -Force | Out-Null
        $orphanQuarterlyDump = Join-Path (
            $quarterlyRoot
        ) "subnetory-quarterly-20000101T000000Z.dump.gz"
        New-ExpiredArtifact -Path $orphanQuarterlyDump -Content "orphan"

        Start-Sleep -Seconds 2
        $null = Invoke-BackupContainer `
            -Level quarterly `
            -RetentionDays 1 `
            -PasswordFile $passwordFile `
            -ShouldSucceed $false
        Grant-TestRootAccess
    }

    Write-Host "POSTGRES_BACKUP_SUCCESS=PASS"
    Write-Host "SHA256_AND_CATALOGUE=PASS"
    Write-Host "RETENTION_LEVEL_ISOLATION=PASS"
    Write-Host "AUTH_FAILURE_CLEANUP=PASS"
    Write-Host "ORPHAN_ROTATION_FAILURE=PASS"
    Write-Host "SECRET_LOG_REDACTION=PASS"
}
catch {
    throw (Protect-Text $_.Exception.Message)
}
finally {
    if ($serverStarted) {
        & $DockerPath stop --time 10 $script:ServerName *> $null
    }
    if ($networkCreated) {
        & $DockerPath network rm $script:NetworkName *> $null
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
        $temporaryPrefix = [System.IO.Path]::GetFullPath(
            [System.IO.Path]::GetTempPath()
        )
        if (-not $resolvedTestRoot.StartsWith(
            $temporaryPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refus de nettoyer un chemin hors du répertoire temporaire."
        }
        if (-not $IsWindows) {
            & $script:DockerPath run `
                --rm `
                --user "0:0" `
                --volume "${resolvedTestRoot}:/cleanup" `
                --entrypoint /bin/sh `
                $script:PostgresImage `
                -c "chmod -R 0777 /cleanup" *> $null
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
