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
$script:DockerPath = $DockerPath
$script:PostgresImage = $PostgresImage
$script:NetworkName = ""
$script:ServerName = ""
$script:BackupRoot = ""
$script:BackupScriptPath = ""
$script:RestoreScriptPath = ""
$script:NormalizedScriptRoot = ""
$script:PasswordFile = ""
$script:KubernetesWrapperPath = ""

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
            throw "Une donnée sensible apparaît dans les journaux du restore drill."
        }
    }
}

function Invoke-RestoreContainer {
    param(
        [Parameter(Mandatory = $true)][string]$Level,
        [Parameter(Mandatory = $true)][string]$BackupFile,
        [Parameter(Mandatory = $true)][string]$WitnessHash,
        [Parameter(Mandatory = $true)][bool]$ShouldSucceed
    )

    $output = (& $script:DockerPath run `
        --rm `
        --network none `
        --user "70:70" `
        --read-only `
        --tmpfs "/tmp:rw,nosuid,nodev,size=64m,uid=70,gid=70,mode=0700" `
        --tmpfs "/var/lib/postgresql/data:rw,nosuid,nodev,size=384m,uid=70,gid=70,mode=0700" `
        --cap-drop ALL `
        --security-opt no-new-privileges `
        --volume "${script:BackupRoot}:/backups:ro" `
        --volume "${script:RestoreScriptPath}:/opt/subnetory-restore/restore-drill.sh:ro" `
        --env "BACKUP_LEVEL=$Level" `
        --env "BACKUP_FILE=$BackupFile" `
        --env WITNESS_ENABLED=true `
        --env WITNESS_ID=232 `
        --env "WITNESS_EXPECTED_SHA256=$WitnessHash" `
        --env EXPECTED_FLYWAY_VERSION=9 `
        --entrypoint /bin/sh `
        $script:PostgresImage `
        /opt/subnetory-restore/restore-drill.sh 2>&1) -join "`n"
    $exitCode = $LASTEXITCODE
    Assert-NoSecret -Text $output

    if ($ShouldSucceed -and $exitCode -ne 0) {
        throw "Le restore drill a échoué : $(Protect-Text $output)"
    }
    if (-not $ShouldSucceed -and $exitCode -eq 0) {
        throw "Une entrée hostile a été acceptée par le restore drill."
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Log = Protect-Text $output
    }
}

function Assert-KubernetesWrapperRejects {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Arguments,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    $rejected = $false
    try {
        & $script:KubernetesWrapperPath @Arguments *> $null
    }
    catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Le wrapper Kubernetes a accepté le scénario hostile : $Scenario."
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
        throw "Préparation des droits des artefacts de restore drill impossible."
    }
}

function Copy-ShellScriptForContainer {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$FileName
    )

    if ([string]::IsNullOrWhiteSpace($script:NormalizedScriptRoot)) {
        throw "Le dossier temporaire de scripts normalisés n'est pas initialisé."
    }

    $TargetPath = Join-Path $script:NormalizedScriptRoot $FileName
    $Content = [System.IO.File]::ReadAllText($SourcePath)
    $Content = $Content -replace "`r`n", "`n"
    $Content = $Content -replace "`r", "`n"

    [System.IO.File]::WriteAllText(
        $TargetPath,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )

    return $TargetPath
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
$script:RestoreScriptPath = (
    Resolve-Path (
        Join-Path $RepositoryPath "charts\subnetory\files\restore-drill.sh"
    )
).Path
$script:KubernetesWrapperPath = (
    Resolve-Path (
        Join-Path $RepositoryPath "scripts\restore-drill-postgres-kubernetes.ps1"
    )
).Path

$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$script:NetworkName = "subnetory-restore-$runId"
$script:ServerName = "subnetory-restore-source-$runId"
$testRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) "subnetory-restore-drill-test-$runId"
$script:BackupRoot = Join-Path $testRoot "backups"
$secretRoot = Join-Path $testRoot "secrets"
$script:NormalizedScriptRoot = Join-Path $testRoot "scripts"
$script:PasswordFile = Join-Path $secretRoot "postgres-password"
$postgresPassword = "S2_32_restore_$runId"
$witnessValue = "restore-witness-$runId"
$script:SecretValues.Add($postgresPassword)
$script:SecretValues.Add($witnessValue)
$witnessHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes($witnessValue)
    )
).ToLowerInvariant()
$networkCreated = $false
$serverStarted = $false

try {
    $validWrapperArguments = @{
        BackupLevel = "daily"
        BackupFile = "subnetory-daily-20260722T020000Z.dump.gz"
        Namespace = "subnetory"
        ReleaseName = "subnetory"
        TimeoutSeconds = 60
    }
    $arguments = $validWrapperArguments.Clone()
    $arguments.BackupFile = "../subnetory-daily-20260722T020000Z.dump.gz"
    Assert-KubernetesWrapperRejects `
        -Arguments $arguments `
        -Scenario "traversée de chemin"
    $arguments = $validWrapperArguments.Clone()
    $arguments.Namespace = "subnetory;whoami"
    Assert-KubernetesWrapperRejects `
        -Arguments $arguments `
        -Scenario "injection par namespace"
    $arguments = $validWrapperArguments.Clone()
    $arguments.ReleaseName = 'subnetory$(whoami)'
    Assert-KubernetesWrapperRejects `
        -Arguments $arguments `
        -Scenario "injection par release"
    $arguments = $validWrapperArguments.Clone()
    $arguments.WitnessId = 232
    $arguments.WitnessExpectedSha256 = "not-a-sha256"
    Assert-KubernetesWrapperRejects `
        -Arguments $arguments `
        -Scenario "empreinte témoin invalide"
    $arguments = $validWrapperArguments.Clone()
    $arguments.Kubectl = "pwsh"
    Assert-KubernetesWrapperRejects `
        -Arguments $arguments `
        -Scenario "exécutable arbitraire"

    & $DockerPath version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker est indisponible."
    }
    & $DockerPath image inspect $PostgresImage *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "L'image PostgreSQL épinglée est absente : $PostgresImage"
    }

    New-Item -ItemType Directory -Path $script:BackupRoot, $secretRoot, $script:NormalizedScriptRoot -Force |
        Out-Null

    $script:BackupScriptPath = Copy-ShellScriptForContainer `
        -SourcePath $script:BackupScriptPath `
        -FileName "backup.sh"
    $script:RestoreScriptPath = Copy-ShellScriptForContainer `
        -SourcePath $script:RestoreScriptPath `
        -FileName "restore-drill.sh"

    if (-not $IsWindows) {
        & chmod 0777 $script:BackupRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Préparation des droits du dossier de sauvegarde impossible."
        }
    }
    [System.IO.File]::WriteAllText(
        $script:PasswordFile,
        $postgresPassword,
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
        --volume "${script:PasswordFile}:/run/secrets/postgres-password:ro" `
        --env POSTGRES_DB=subnetory `
        --env POSTGRES_USER=subnetory `
        --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password `
        $PostgresImage) -join ""
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverId)) {
        throw "Démarrage du PostgreSQL source impossible."
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
        throw "PostgreSQL source non prêt."
    }

    $seedSql = @"
CREATE TABLE flyway_schema_history (
  installed_rank integer PRIMARY KEY,
  version varchar(50),
  success boolean NOT NULL
);
INSERT INTO flyway_schema_history VALUES (9, '9', true);
CREATE TABLE public.subnetory_restore_probe (
  id integer PRIMARY KEY,
  marker text NOT NULL
);
INSERT INTO public.subnetory_restore_probe VALUES (232, '$witnessValue');
"@
    $seedOutput = ($seedSql | & $DockerPath exec --interactive `
        $script:ServerName `
        psql `
        --host=127.0.0.1 `
        --username=subnetory `
        --dbname=subnetory `
        --set ON_ERROR_STOP=1 2>&1) -join "`n"
    Assert-NoSecret -Text $seedOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Création des données témoins impossible : $(Protect-Text $seedOutput)"
    }

    $backupOutput = (& $DockerPath run `
        --rm `
        --network $script:NetworkName `
        --user "70:70" `
        --read-only `
        --tmpfs "/tmp:rw,noexec,nosuid,nodev,size=16m" `
        --cap-drop ALL `
        --security-opt no-new-privileges `
        --volume "${script:BackupRoot}:/backups" `
        --volume "${script:BackupScriptPath}:/opt/subnetory-backup/backup.sh:ro" `
        --volume "${script:PasswordFile}:/run/secrets/postgres-password:ro" `
        --env BACKUP_LEVEL=daily `
        --env RETENTION_DAYS=14 `
        --env BACKUP_ROOT=/backups `
        --env "PGHOST=$($script:ServerName)" `
        --env PGPORT=5432 `
        --env PGDATABASE=subnetory `
        --env PGUSER=subnetory `
        --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password `
        --entrypoint /bin/sh `
        $PostgresImage `
        /opt/subnetory-backup/backup.sh 2>&1) -join "`n"
    Assert-NoSecret -Text $backupOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Création de la sauvegarde impossible : $(Protect-Text $backupOutput)"
    }
    Grant-TestRootAccess

    $dailyRoot = Join-Path $script:BackupRoot "daily"
    $dumps = @(Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
        $_.Name.EndsWith(".dump.gz", [System.StringComparison]::Ordinal)
    })
    $checksums = @(
        Get-ChildItem -LiteralPath $dailyRoot -File | Where-Object {
            $_.Name.EndsWith(".dump.gz.sha256", [System.StringComparison]::Ordinal)
        }
    )
    if ($dumps.Count -ne 1 -or $checksums.Count -ne 1) {
        throw "Le couple de sauvegarde à restaurer est ambigu ou incomplet."
    }
    $backupFile = $dumps[0].Name

    $success = Invoke-RestoreContainer `
        -Level daily `
        -BackupFile $backupFile `
        -WitnessHash $witnessHash `
        -ShouldSucceed $true
    if ($success.Log -notmatch "Restore drill completed:") {
        throw "La preuve de restauration attendue est absente."
    }

    $pathTraversal = Invoke-RestoreContainer `
        -Level daily `
        -BackupFile "../$backupFile" `
        -WitnessHash $witnessHash `
        -ShouldSucceed $false
    if ($pathTraversal.Log -notmatch "backup filename is not allowed") {
        throw "Le rejet de traversée de chemin n'est pas explicite."
    }

    $levelConfusion = Invoke-RestoreContainer `
        -Level hourly `
        -BackupFile $backupFile `
        -WitnessHash $witnessHash `
        -ShouldSucceed $false
    if ($levelConfusion.Log -notmatch "does not match its level") {
        throw "Le rejet de confusion de niveau n'est pas explicite."
    }

    $originalChecksum = [System.IO.File]::ReadAllText($checksums[0].FullName)
    $maliciousChecksum = (
        $originalChecksum.TrimEnd("`r", "`n") + "`n" +
        ("0" * 64) + "  `$(`$(touch /tmp/subnetory-injected))`n"
    )
    [System.IO.File]::WriteAllText(
        $checksums[0].FullName,
        $maliciousChecksum,
        [System.Text.UTF8Encoding]::new($false)
    )
    $checksumAttack = Invoke-RestoreContainer `
        -Level daily `
        -BackupFile $backupFile `
        -WitnessHash $witnessHash `
        -ShouldSucceed $false
    if ($checksumAttack.Log -notmatch "exactly one line") {
        throw "Le rejet du fichier de contrôle hostile n'est pas explicite."
    }

    Write-Host "RESTORE_DRILL_DOCKER=PASS"
    Write-Host "NETWORK_NONE_AND_READ_ONLY=PASS"
    Write-Host "NON_SUPERUSER_RESTORE=PASS"
    Write-Host "FLYWAY_AND_WITNESS_SHA256=PASS"
    Write-Host "PATH_TRAVERSAL_REJECTED=PASS"
    Write-Host "LEVEL_CONFUSION_REJECTED=PASS"
    Write-Host "MALICIOUS_CHECKSUM_REJECTED=PASS"
    Write-Host "KUBERNETES_PARAMETER_INJECTION_REJECTED=PASS"
    Write-Host "SENSITIVE_LOG_REDACTION=PASS"
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
        if (-not $IsWindows -and -not [string]::IsNullOrWhiteSpace($script:BackupRoot)) {
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
