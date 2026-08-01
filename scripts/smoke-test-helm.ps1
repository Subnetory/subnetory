#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$RepositoryPath,
    [string]$KindPath = "kind",
    [string]$KubectlPath = "kubectl",
    [string]$HelmPath = "helm",
    [ValidateSet("All", "Included", "External")]
    [string]$Profile = "All",
    [string]$RedactedLogPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true

$requiredKindVersion = "v0.32.0"
$requiredKubectlVersion = "v1.34.8"
$requiredHelmVersion = "v3.21.0"
$nodeImage = "kindest/node:v1.34.8@sha256:02722c2dedddcfc00febf5d27fbeb9b7b2c14294c82109ff4a85d89ac9ba3256"
$postgresImage = "postgres:17.10-alpine3.23"
$script:SecretValues = [System.Collections.Generic.List[string]]::new()
$script:PortForward = $null
$script:SmokeStep = "initialisation"

function Get-ExpectedFlywayState {
    $RootPath = $RepositoryPath
    if ([string]::IsNullOrWhiteSpace($RootPath)) {
        $RootPath = Split-Path -Parent $PSScriptRoot
    }

    $MigrationPath = Join-Path $RootPath "backend\src\main\resources\db\migration"
    $Migrations = @(Get-ChildItem -LiteralPath $MigrationPath -Filter "V*.sql" |
        ForEach-Object {
            $Match = [regex]::Match($_.Name, '^V(?<Version>[0-9]+)__.+\.sql$')
            if ($Match.Success) {
                [pscustomobject]@{
                    Version = [int]$Match.Groups["Version"].Value
                    Name = $_.Name
                }
            }
        } |
        Sort-Object Version)

    if ($Migrations.Count -lt 1) {
        throw "Aucune migration Flyway versionnée trouvée."
    }

    return [pscustomobject]@{
        Count = $Migrations.Count
        LatestVersion = [string]$Migrations[-1].Version
    }
}

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

function New-Base64UrlSecret {
    param([ValidateRange(1, 4096)][int]$ByteCount)

    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
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

function Convert-ResponseContentToString {
    param([Parameter(Mandatory = $true)]$Content)

    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$Content)
    }
    return [string]$Content
}

function Get-PodName {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$Release,
        [Parameter(Mandatory = $true)][string]$Component
    )

    $selector = (
        "app.kubernetes.io/instance=$Release," +
        "app.kubernetes.io/component=$Component"
    )
    $name = (& $KubectlPath get pods `
        --namespace $Namespace `
        --selector $selector `
        --output "jsonpath={.items[0].metadata.name}").Trim()

    if ([string]::IsNullOrWhiteSpace($name)) {
        throw "Pod introuvable pour $Release/$Component dans $Namespace."
    }
    return $name
}

function Get-SecretValue {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$SecretName,
        [Parameter(Mandatory = $true)][string]$Key
    )

    $encoded = (& $KubectlPath get secret $SecretName `
        --namespace $Namespace `
        --output "jsonpath={.data.$Key}").Trim()
    if ([string]::IsNullOrWhiteSpace($encoded)) {
        throw "Clé secrète absente : $SecretName/$Key."
    }
    $value = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
    $script:SecretValues.Add($value)
    return $value
}

function Stop-PortForward {
    if ($null -ne $script:PortForward) {
        try {
            $script:PortForward.Refresh()
            if (-not $script:PortForward.HasExited) {
                $script:PortForward.Kill($true)
                $script:PortForward.WaitForExit(5000) | Out-Null
            }
        }
        catch {
        }
        finally {
            $script:PortForward.Dispose()
            $script:PortForward = $null
        }
    }
}

function Start-ApplicationPortForward {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$Release
    )

    Stop-PortForward
    $pod = Get-PodName -Namespace $Namespace -Release $Release -Component "application"
    $port = Get-FreeTcpPort
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $KubectlPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    foreach ($argument in @(
        "port-forward",
        "--namespace", $Namespace,
        "pod/$pod",
        "${port}:8080"
    )) {
        $startInfo.ArgumentList.Add($argument)
    }

    $script:PortForward = [System.Diagnostics.Process]::new()
    $script:PortForward.StartInfo = $startInfo
    if (-not $script:PortForward.Start()) {
        throw "Impossible de démarrer kubectl port-forward."
    }

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if ($script:PortForward.HasExited) {
            $stderr = $script:PortForward.StandardError.ReadToEnd()
            throw "kubectl port-forward a échoué : $(Protect-Text $stderr)"
        }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $client.Connect("127.0.0.1", $port)
            return "http://127.0.0.1:$port"
        }
        catch {
            Start-Sleep -Milliseconds 300
        }
        finally {
            $client.Dispose()
        }
    }
    throw "Le port-forward n'est pas devenu disponible."
}

function Wait-HealthStatus {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$Endpoint,
        [Parameter(Mandatory = $true)][string]$Expected,
        [int]$TimeoutSeconds = 180
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = "aucune réponse"
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest `
                -Uri "$BaseUri/actuator/health/$Endpoint" `
                -TimeoutSec 30 `
                -SkipHttpErrorCheck
            $body = Convert-ResponseContentToString -Content $response.Content
            $status = ($body | ConvertFrom-Json).status
            $last = "HTTP $($response.StatusCode), status=$status"
            if ($status -eq $Expected) {
                return
            }
        }
        catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "$Endpoint n'a pas atteint $Expected : $last"
}

function Get-CsrfToken {
    param([Parameter(Mandatory = $true)][string]$Html)

    foreach ($pattern in @(
        '<input[^>]+name=["'']_csrf["''][^>]+value=["'']([^"'']+)["'']',
        '<input[^>]+value=["'']([^"'']+)["''][^>]+name=["'']_csrf["'']'
    )) {
        $match = [regex]::Match($Html, $pattern)
        if ($match.Success) {
            return $match.Groups[1].Value
        }
    }
    throw "Jeton CSRF introuvable."
}

function Get-FinalPath {
    param([Parameter(Mandatory = $true)]$Response)
    return $Response.BaseResponse.RequestMessage.RequestUri.AbsolutePath
}

function Invoke-BootstrapLogin {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$BootstrapPassword,
        [AllowNull()][string]$NewPassword
    )

    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $loginPage = Invoke-WebRequest -Uri "$BaseUri/login" -WebSession $session
    $csrf = Get-CsrfToken -Html (
        Convert-ResponseContentToString -Content $loginPage.Content
    )
    $login = Invoke-WebRequest `
        -Uri "$BaseUri/login" `
        -Method Post `
        -WebSession $session `
        -Body @{
            username = "admin"
            password = $BootstrapPassword
            _csrf = $csrf
        }

    if ((Get-FinalPath -Response $login) -ne "/profile/change-password-required") {
        throw "Le login bootstrap n'impose pas le changement de mot de passe."
    }

    if ([string]::IsNullOrEmpty($NewPassword)) {
        return
    }

    $blocked = Invoke-WebRequest -Uri "$BaseUri/" -WebSession $session
    if ((Get-FinalPath -Response $blocked) -ne "/profile/change-password-required") {
        throw "Le dashboard est accessible avant le changement de mot de passe."
    }

    $temporaryTokenBody = @{
        username = "admin"
        password = $BootstrapPassword
    } | ConvertTo-Json -Compress
    $temporaryToken = Invoke-WebRequest `
        -Uri "$BaseUri/api/v1/auth/token" `
        -Method Post `
        -ContentType "application/json" `
        -Body $temporaryTokenBody `
        -SkipHttpErrorCheck
    $temporaryTokenContent = Convert-ResponseContentToString `
        -Content $temporaryToken.Content
    if ($temporaryToken.StatusCode -ne 403 -or
        (($temporaryTokenContent | ConvertFrom-Json).code -ne "PASSWORD_CHANGE_REQUIRED")) {
        throw "Le JWT bootstrap n'est pas bloqué comme attendu."
    }

    $changePage = Invoke-WebRequest `
        -Uri "$BaseUri/profile/change-password-required" `
        -WebSession $session
    $changeCsrf = Get-CsrfToken -Html (
        Convert-ResponseContentToString -Content $changePage.Content
    )
    $changed = Invoke-WebRequest `
        -Uri "$BaseUri/profile/change-password-required" `
        -Method Post `
        -WebSession $session `
        -Body @{
            currentPassword = $BootstrapPassword
            newPassword = $NewPassword
            confirmPassword = $NewPassword
            _csrf = $changeCsrf
        }
    if ((Get-FinalPath -Response $changed) -ne "/") {
        throw "Le changement obligatoire n'a pas rendu le dashboard accessible."
    }
}

function Assert-JwtLogin {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$Password
    )

    $body = @{username = "admin"; password = $Password} | ConvertTo-Json -Compress
    $response = Invoke-WebRequest `
        -Uri "$BaseUri/api/v1/auth/token" `
        -Method Post `
        -ContentType "application/json" `
        -Body $body `
        -SkipHttpErrorCheck
    $responseContent = Convert-ResponseContentToString -Content $response.Content
    if ($response.StatusCode -ne 200 -or
        [string]::IsNullOrWhiteSpace(($responseContent | ConvertFrom-Json).accessToken)) {
        throw "Le login JWT avec le mot de passe courant a échoué."
    }
}

function Assert-FlywayCurrent {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$Pod
    )

    $expectedFlyway = Get-ExpectedFlywayState
    $state = (& $KubectlPath exec --namespace $Namespace $Pod -- `
        psql -U subnetory -d subnetory -tAc `
        "SELECT count(*) || ':' || (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1) FROM flyway_schema_history WHERE success;").Trim()

    $expectedState = "$($expectedFlyway.Count):$($expectedFlyway.LatestVersion)"
    if ($state -ne $expectedState) {
        throw "État Flyway attendu '$expectedState', obtenu : '$state'."
    }
}

function Install-TestSecrets {
    param([Parameter(Mandatory = $true)][string]$Namespace)

    & (Join-Path $RepositoryPath "scripts\init-helm-secrets.ps1") `
        -Namespace $Namespace `
        -Kubectl $KubectlPath
}

function Invoke-ScheduledBackupRestoreDrill {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$Release,
        [Parameter(Mandatory = $true)][string]$PostgresPod
    )

    $script:SmokeStep = "profil-inclus/sauvegarde/controle-securite"
    $cronJobName = "$Release-backups-daily"
    $cronJob = (& $KubectlPath get cronjob $cronJobName `
        --namespace $Namespace `
        --output json | ConvertFrom-Json)
    $podSpec = $cronJob.spec.jobTemplate.spec.template.spec
    $enabledHostNamespaces = @(
        "hostNetwork",
        "hostPID",
        "hostIPC",
        "shareProcessNamespace"
    ) | Where-Object {
        $podSpec.PSObject.Properties.Name -contains $_ -and
        $podSpec.$_ -ne $false
    }
    if (
        $podSpec.automountServiceAccountToken -ne $false -or
        @($enabledHostNamespaces).Count -ne 0 -or
        $podSpec.securityContext.runAsNonRoot -ne $true -or
        $podSpec.securityContext.seccompProfile.type -ne "RuntimeDefault" -or
        @($podSpec.containers).Count -ne 1
    ) {
        throw "Le CronJob de sauvegarde ne respecte pas l'isolation attendue."
    }
    $backupContainer = $podSpec.containers[0]
    if (
        $backupContainer.securityContext.allowPrivilegeEscalation -ne $false -or
        $backupContainer.securityContext.readOnlyRootFilesystem -ne $true -or
        "ALL" -notin @($backupContainer.securityContext.capabilities.drop) -or
        @($backupContainer.env | Where-Object {
            $_.name -in @("PGPASSWORD", "POSTGRES_PASSWORD") -or
            $_.PSObject.Properties.Name -contains "valueFrom"
        }).Count -ne 0
    ) {
        throw "Le conteneur de sauvegarde expose une capacité ou un secret interdit."
    }

    $witnessValue = "helm-restore-" + [Guid]::NewGuid().ToString("N")
    $script:SecretValues.Add($witnessValue)
    $witnessHash = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData(
            [System.Text.Encoding]::UTF8.GetBytes($witnessValue)
        )
    ).ToLowerInvariant()
    $seedSql = @"
CREATE TABLE public.subnetory_restore_probe (
  id integer PRIMARY KEY,
  marker text NOT NULL
);
INSERT INTO public.subnetory_restore_probe VALUES (232, '$witnessValue');
"@
    $seedOutput = ($seedSql | & $KubectlPath exec --stdin `
        --namespace $Namespace `
        $PostgresPod `
        -- psql -U subnetory -d subnetory -v ON_ERROR_STOP=1 2>&1) -join "`n"
    if ($seedOutput.Contains($witnessValue)) {
        throw "La donnée témoin apparaît dans la sortie de préparation."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Création de la donnée témoin de restauration impossible."
    }

    $jobName = "subnetory-backup-smoke-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $jobCreated = $false
    try {
        $script:SmokeStep = "profil-inclus/sauvegarde/execution"
        & $KubectlPath create job $jobName `
            --namespace $Namespace `
            --from="cronjob/$cronJobName" | Out-Null
        $jobCreated = $true
        & $KubectlPath wait "job/$jobName" `
            --namespace $Namespace `
            --for=condition=Complete `
            --timeout=600s | Out-Null

        $backupLogs = (& $KubectlPath logs "job/$jobName" `
            --namespace $Namespace 2>&1) -join "`n"
        foreach ($secret in $script:SecretValues) {
            if (
                -not [string]::IsNullOrWhiteSpace($secret) -and
                $backupLogs.Contains($secret)
            ) {
                throw "Une valeur sensible apparaît dans les journaux de sauvegarde."
            }
        }
        $matches = [regex]::Matches(
            $backupLogs,
            'Backup completed: (subnetory-daily-[0-9]{8}T[0-9]{6}Z\.dump\.gz)'
        )
        if ($matches.Count -ne 1) {
            throw "Le nom sûr et unique de la sauvegarde est introuvable."
        }
        $backupFile = $matches[0].Groups[1].Value

        $script:SmokeStep = "profil-inclus/restauration/isolee"
        $restoreOutput = (& (Join-Path `
            $RepositoryPath `
            "scripts\restore-drill-postgres-kubernetes.ps1") `
            -BackupLevel daily `
            -BackupFile $backupFile `
            -Namespace $Namespace `
            -ReleaseName $Release `
            -WitnessId 232 `
            -WitnessExpectedSha256 $witnessHash `
            -TimeoutSeconds 600 `
            -Kubectl $KubectlPath 2>&1) -join "`n"
        foreach ($expectedProof in @(
            "RESTORE_DRILL_ISOLATED=PASS",
            "RESTORE_DRILL_FLYWAY=PASS",
            "RESTORE_DRILL_WITNESS=PASS"
        )) {
            if ($restoreOutput -notmatch [regex]::Escape($expectedProof)) {
                throw "Preuve de restauration absente : $expectedProof."
            }
        }
        foreach ($secret in $script:SecretValues) {
            if (
                -not [string]::IsNullOrWhiteSpace($secret) -and
                $restoreOutput.Contains($secret)
            ) {
                throw "Une valeur sensible apparaît dans le restore drill."
            }
        }

        $sourceWitness = (& $KubectlPath exec `
            --namespace $Namespace `
            $PostgresPod `
            -- psql -U subnetory -d subnetory -tAc `
            "SELECT marker FROM public.subnetory_restore_probe WHERE id = 232;").Trim()
        if ($sourceWitness -ne $witnessValue) {
            throw "Le restore drill a modifié la base PostgreSQL opérationnelle."
        }
        $leftovers = (& $KubectlPath get job,networkpolicy,configmap `
            --namespace $Namespace `
            --selector "app.kubernetes.io/component=restore-drill" `
            --output name) -join "`n"
        if (-not [string]::IsNullOrWhiteSpace($leftovers)) {
            throw "Le restore drill a laissé des ressources temporaires."
        }
    }
    finally {
        if ($jobCreated) {
            & $KubectlPath delete job $jobName `
                --namespace $Namespace `
                --ignore-not-found=true `
                --wait=true *> $null
        }
    }
}

function Invoke-IncludedProfile {
    param(
        [Parameter(Mandatory = $true)][string]$ChartPath,
        [Parameter(Mandatory = $true)][string]$ImageRepository,
        [Parameter(Mandatory = $true)][string]$ImageTag
    )

    $namespace = "subnetory-helm-included"
    $release = "subnetory"
    & $KubectlPath create namespace $namespace | Out-Null
    Install-TestSecrets -Namespace $namespace
    $bootstrapPassword = Get-SecretValue `
        -Namespace $namespace `
        -SecretName "subnetory-bootstrap-secrets" `
        -Key "admin-default-password"
    # Prefix guarantees every category required by PasswordPolicyService while
    # the random suffix preserves high entropy and uniqueness.
    $newPassword = "Aa1!" + (New-Base64UrlSecret -ByteCount 32)
    $script:SecretValues.Add($newPassword)

    # A dynamically provisioned backup PVC may use WaitForFirstConsumer.
    # Helm --wait would then wait for that PVC before a scheduled backup pod
    # can consume it. Readiness is checked explicitly below, and the backup
    # Job binds the PVC before subsequent wait-enabled upgrades.
    $script:SmokeStep = "profil-inclus/installation"
    & $HelmPath install $release $ChartPath `
        --namespace $namespace `
        --set "image.repository=$ImageRepository" `
        --set "image.tag=$ImageTag" `
        --set image.pullPolicy=Never `
        --set backup.enabled=true | Out-Null

    $script:SmokeStep = "profil-inclus/readiness-initiale"
    & $KubectlPath rollout status deployment/$release `
        --namespace $namespace `
        --timeout=300s | Out-Null
    & $KubectlPath rollout status statefulset/$release-postgresql `
        --namespace $namespace `
        --timeout=300s | Out-Null

    $appPod = Get-PodName -Namespace $namespace -Release $release -Component "application"
    $postgresPod = Get-PodName -Namespace $namespace -Release $release -Component "postgresql"
    Assert-FlywayCurrent -Namespace $namespace -Pod $postgresPod

    $appIdentity = (& $KubectlPath exec --namespace $namespace $appPod -- id).Trim()
    $postgresIdentity = (& $KubectlPath exec --namespace $namespace $postgresPod -- id).Trim()
    if ($appIdentity -notmatch "uid=10001.*gid=10001") {
        throw "Identité applicative invalide : $appIdentity"
    }
    if ($postgresIdentity -notmatch "uid=70.*gid=70") {
        throw "Identité PostgreSQL invalide : $postgresIdentity"
    }
    $modes = (& $KubectlPath exec --namespace $namespace $appPod -- `
        ls -lnL `
        /run/secrets/runtime/subnetory.jwt.secret `
        /run/secrets/runtime/spring.datasource.password `
        /run/secrets/bootstrap/subnetory.admin.default-password) -join "`n"
    if (([regex]::Matches($modes, "(?m)^-r--r-----\s+1\s+0\s+10001\s+")).Count -ne 3) {
        throw "Permissions 0440/groupe 10001 non confirmées sur les trois fichiers Secret."
    }

    $baseUri = Start-ApplicationPortForward -Namespace $namespace -Release $release
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected UP
    Invoke-BootstrapLogin `
        -BaseUri $baseUri `
        -BootstrapPassword $bootstrapPassword `
        -NewPassword $newPassword
    Assert-JwtLogin -BaseUri $baseUri -Password $newPassword

    & $KubectlPath delete secret subnetory-bootstrap-secrets `
        --namespace $namespace `
        --wait=true | Out-Null

    Invoke-ScheduledBackupRestoreDrill `
        -Namespace $namespace `
        -Release $release `
        -PostgresPod $postgresPod

    & $HelmPath test $release `
        --namespace $namespace `
        --logs `
        --timeout 5m | Out-Null

    Stop-PortForward
    $upgradeMarker = [Guid]::NewGuid().ToString("N")
    & $HelmPath upgrade $release $ChartPath `
        --namespace $namespace `
        --reuse-values `
        --set-string "podAnnotations.smoke-upgrade=$upgradeMarker" `
        --wait `
        --timeout 10m | Out-Null
    & $HelmPath rollback $release 1 `
        --namespace $namespace `
        --wait `
        --timeout 10m | Out-Null

    $appPod = Get-PodName -Namespace $namespace -Release $release -Component "application"
    & $KubectlPath delete pod $appPod --namespace $namespace --wait=true | Out-Null
    & $KubectlPath rollout status deployment/$release `
        --namespace $namespace `
        --timeout=300s | Out-Null
    $baseUri = Start-ApplicationPortForward -Namespace $namespace -Release $release
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected UP
    Assert-JwtLogin -BaseUri $baseUri -Password $newPassword

    $postgresPod = Get-PodName -Namespace $namespace -Release $release -Component "postgresql"
    & $KubectlPath exec --namespace $namespace $postgresPod -- `
        psql -U subnetory -d subnetory -v ON_ERROR_STOP=1 -c `
        "CREATE TABLE helm_persistence_probe (id integer PRIMARY KEY); INSERT INTO helm_persistence_probe VALUES (231);" | Out-Null
    & $KubectlPath delete pod $postgresPod --namespace $namespace --wait=true | Out-Null
    & $KubectlPath rollout status statefulset/$release-postgresql `
        --namespace $namespace `
        --timeout=300s | Out-Null
    $postgresPod = Get-PodName -Namespace $namespace -Release $release -Component "postgresql"
    $marker = (& $KubectlPath exec --namespace $namespace $postgresPod -- `
        psql -U subnetory -d subnetory -tAc `
        "SELECT id FROM helm_persistence_probe;").Trim()
    if ($marker -ne "231") {
        throw "La donnée témoin n'a pas survécu au redémarrage PostgreSQL."
    }
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected UP
    Assert-JwtLogin -BaseUri $baseUri -Password $newPassword

    $script:SmokeStep = "profil-inclus/panne-base/arret"
    & $KubectlPath scale statefulset/$release-postgresql `
        --namespace $namespace `
        --replicas=0 | Out-Null
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
    do {
        $postgresPodNames = & $KubectlPath get pods `
            --namespace $namespace `
            --selector "app.kubernetes.io/component=postgresql" `
            --output "jsonpath={.items[*].metadata.name}"
        $count = [string]$postgresPodNames
        if ([string]::IsNullOrWhiteSpace($count)) {
            break
        }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if (-not [string]::IsNullOrWhiteSpace($count)) {
        throw "Le pod PostgreSQL n'a pas été arrêté."
    }
    $script:SmokeStep = "profil-inclus/panne-base/readiness-down"
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected DOWN
    $script:SmokeStep = "profil-inclus/panne-base/liveness-up"
    Wait-HealthStatus -BaseUri $baseUri -Endpoint liveness -Expected UP

    $script:SmokeStep = "profil-inclus/panne-base/redemarrage"
    & $KubectlPath scale statefulset/$release-postgresql `
        --namespace $namespace `
        --replicas=1 | Out-Null
    & $KubectlPath rollout status statefulset/$release-postgresql `
        --namespace $namespace `
        --timeout=300s | Out-Null
    $script:SmokeStep = "profil-inclus/panne-base/readiness-reprise"
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected UP
    $script:SmokeStep = "profil-inclus/panne-base/auth-reprise"
    Assert-JwtLogin -BaseUri $baseUri -Password $newPassword

    $script:SmokeStep = "profil-inclus/retention-pvc"
    Stop-PortForward
    $postgresPvc = (& $KubectlPath get pvc `
        --namespace $namespace `
        --selector "app.kubernetes.io/component=postgresql" `
        --output "jsonpath={.items[0].metadata.name}").Trim()
    $backupPvc = (& $KubectlPath get pvc `
        --namespace $namespace `
        --selector "app.kubernetes.io/component=backup" `
        --output "jsonpath={.items[0].metadata.name}").Trim()
    if ([string]::IsNullOrWhiteSpace($postgresPvc)) {
        throw "PVC PostgreSQL introuvable avant helm uninstall."
    }
    if ([string]::IsNullOrWhiteSpace($backupPvc)) {
        throw "PVC de sauvegarde introuvable avant helm uninstall."
    }
    & $HelmPath uninstall $release --namespace $namespace --wait | Out-Null
    & $KubectlPath get pvc $postgresPvc `
        --namespace $namespace `
        --output name | Out-Null
    & $KubectlPath get pvc $backupPvc `
        --namespace $namespace `
        --output name | Out-Null
    & $KubectlPath delete pvc $postgresPvc $backupPvc `
        --namespace $namespace `
        --wait=true | Out-Null

    Write-Host "Profil A validé : auth, Flyway, sauvegarde, restauration isolée, pannes et rétention PVC."
}

function Invoke-ExternalProfile {
    param(
        [Parameter(Mandatory = $true)][string]$ChartPath,
        [Parameter(Mandatory = $true)][string]$ImageRepository,
        [Parameter(Mandatory = $true)][string]$ImageTag
    )

    $namespace = "subnetory-helm-external"
    $release = "subnetory-external"
    & $KubectlPath create namespace $namespace | Out-Null
    Install-TestSecrets -Namespace $namespace

    $externalManifest = @"
apiVersion: v1
kind: Service
metadata:
  name: external-postgresql
  namespace: $namespace
spec:
  selector:
    app: external-postgresql
  ports:
    - name: postgresql
      port: 5432
      targetPort: postgresql
---
apiVersion: v1
kind: Pod
metadata:
  name: external-postgresql
  namespace: $namespace
  labels:
    app: external-postgresql
spec:
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 70
    runAsGroup: 70
    fsGroup: 70
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: postgresql
      image: $postgresImage
      securityContext:
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
        readOnlyRootFilesystem: true
      env:
        - name: POSTGRES_DB
          value: subnetory
        - name: POSTGRES_USER
          value: subnetory
        - name: POSTGRES_PASSWORD_FILE
          value: /run/secrets/postgres-password
        - name: PGDATA
          value: /var/lib/postgresql/data/pgdata
      ports:
        - name: postgresql
          containerPort: 5432
      readinessProbe:
        exec:
          command: ["pg_isready", "-U", "subnetory", "-d", "subnetory"]
        periodSeconds: 5
      volumeMounts:
        - name: data
          mountPath: /var/lib/postgresql/data
        - name: runtime-secrets
          mountPath: /run/secrets
          readOnly: true
        - name: temporary-directory
          mountPath: /tmp
        - name: runtime-directory
          mountPath: /var/run/postgresql
  volumes:
    - name: data
      emptyDir: {}
    - name: runtime-secrets
      secret:
        secretName: subnetory-runtime-secrets
        defaultMode: 0440
        items:
          - key: postgres-password
            path: postgres-password
    - name: temporary-directory
      emptyDir: {}
    - name: runtime-directory
      emptyDir: {}
"@
    $externalManifest | & $KubectlPath apply --filename - | Out-Null
    & $KubectlPath wait pod/external-postgresql `
        --namespace $namespace `
        --for=condition=Ready `
        --timeout=300s | Out-Null

    & $HelmPath install $release $ChartPath `
        --namespace $namespace `
        --set "image.repository=$ImageRepository" `
        --set "image.tag=$ImageTag" `
        --set image.pullPolicy=Never `
        --set postgresql.enabled=false `
        --set externalDatabase.enabled=true `
        --set externalDatabase.host=external-postgresql `
        --set externalDatabase.sslMode=disable `
        --wait `
        --timeout 10m | Out-Null

    & $KubectlPath rollout status deployment/$release `
        --namespace $namespace `
        --timeout=300s | Out-Null
    $unexpectedBackupResources = (& $KubectlPath get cronjob,pvc `
        --namespace $namespace `
        --selector "app.kubernetes.io/instance=$release,app.kubernetes.io/component=backup" `
        --output name) -join "`n"
    if (-not [string]::IsNullOrWhiteSpace($unexpectedBackupResources)) {
        throw "Le profil PostgreSQL externe a créé des ressources de sauvegarde interdites."
    }
    Assert-FlywayCurrent -Namespace $namespace -Pod "external-postgresql"
    $bootstrapPassword = Get-SecretValue `
        -Namespace $namespace `
        -SecretName "subnetory-bootstrap-secrets" `
        -Key "admin-default-password"
    $baseUri = Start-ApplicationPortForward -Namespace $namespace -Release $release
    Wait-HealthStatus -BaseUri $baseUri -Endpoint readiness -Expected UP
    Invoke-BootstrapLogin `
        -BaseUri $baseUri `
        -BootstrapPassword $bootstrapPassword `
        -NewPassword $null
    Stop-PortForward
    & $HelmPath uninstall $release --namespace $namespace --wait | Out-Null
    $expectedFlyway = Get-ExpectedFlywayState
    Write-Host "Profil B validé : PostgreSQL externe, Ready, Flyway V$($expectedFlyway.LatestVersion) et login."
}

function Get-RedactedDiagnostics {
    $sections = [System.Collections.Generic.List[string]]::new()
    try {
        $sections.Add(((& $KubectlPath get pods --all-namespaces --output wide 2>&1) -join "`n"))
        foreach ($namespace in @("subnetory-helm-included", "subnetory-helm-external")) {
            $pods = @(& $KubectlPath get pods --namespace $namespace --output name 2>$null)
            foreach ($pod in $pods) {
                $logs = (& $KubectlPath logs $pod `
                    --namespace $namespace `
                    --all-containers `
                    --tail=200 2>&1) -join "`n"
                $sections.Add("=== $namespace/$pod ===`n$logs")
            }
        }
    }
    catch {
        $sections.Add($_.Exception.Message)
    }
    return Protect-Text ($sections -join "`n`n")
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}
else {
    $RepositoryPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
}

$chartPath = Join-Path $RepositoryPath "charts\subnetory"
$backendPath = Join-Path $RepositoryPath "backend"
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$clusterName = "subnetory-helm-$runId"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "subnetory-helm-smoke-$runId"
$kubeconfig = Join-Path $temporaryRoot "kubeconfig"
$imageRepository = "subnetory"
$imageTag = "helm-smoke-$runId"
$imageReference = "${imageRepository}:${imageTag}"
$previousKubeconfig = $env:KUBECONFIG
$clusterCreated = $false
$imageBuilt = $false

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    $kindVersion = (& $KindPath version) -join ""
    if ($kindVersion -notmatch [regex]::Escape("kind $requiredKindVersion")) {
        throw "kind $requiredKindVersion requis, obtenu : $kindVersion"
    }
    $kubectlVersion = ((& $KubectlPath version --client --output json | ConvertFrom-Json).clientVersion.gitVersion)
    if ($kubectlVersion -ne $requiredKubectlVersion) {
        throw "kubectl $requiredKubectlVersion requis, obtenu : $kubectlVersion"
    }
    $helmVersion = (& $HelmPath version --short) -join ""
    if ($helmVersion -notmatch "^$([regex]::Escape($requiredHelmVersion))(?:\+|$)") {
        throw "Helm $requiredHelmVersion requis, obtenu : $helmVersion"
    }
    docker version *> $null

    & $HelmPath lint $chartPath --strict
    & $HelmPath template static $chartPath --namespace static *> $null

    $env:KUBECONFIG = $kubeconfig
    & $KindPath create cluster `
        --name $clusterName `
        --image $nodeImage `
        --kubeconfig $kubeconfig `
        --wait 180s
    $clusterCreated = $true

    docker build --tag $imageReference $backendPath
    $imageBuilt = $true
    & $KindPath load docker-image $imageReference --name $clusterName

    if ($Profile -in @("All", "Included")) {
        Invoke-IncludedProfile `
            -ChartPath $chartPath `
            -ImageRepository $imageRepository `
            -ImageTag $imageTag
    }
    if ($Profile -in @("All", "External")) {
        Invoke-ExternalProfile `
            -ChartPath $chartPath `
            -ImageRepository $imageRepository `
            -ImageTag $imageTag
    }

    Write-Host "SMOKE TEST HELM VALIDE ($Profile)."
}
catch {
    $message = Protect-Text $_.Exception.Message
    $trace = Protect-Text $_.ScriptStackTrace
    Write-Host "SMOKE TEST HELM EN ECHEC [$script:SmokeStep] : $message" -ForegroundColor Red
    Write-Host $trace
    if ($clusterCreated) {
        $diagnostics = Get-RedactedDiagnostics
        if (-not [string]::IsNullOrWhiteSpace($RedactedLogPath)) {
            $parent = Split-Path -Parent $RedactedLogPath
            if (-not [string]::IsNullOrWhiteSpace($parent)) {
                New-Item -ItemType Directory -Path $parent -Force | Out-Null
            }
            [System.IO.File]::WriteAllText(
                $RedactedLogPath,
                $diagnostics,
                [System.Text.UTF8Encoding]::new($false)
            )
        }
        Write-Host $diagnostics
    }
    throw
}
finally {
    Stop-PortForward
    if ($clusterCreated) {
        & $KindPath delete cluster --name $clusterName *> $null
    }
    if ($imageBuilt) {
        docker image rm $imageReference *> $null
    }
    $env:KUBECONFIG = $previousKubeconfig
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
