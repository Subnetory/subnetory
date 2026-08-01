#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$RepositoryPath,
    [string]$HelmPath = "helm"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

function Invoke-HelmTemplate {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][bool]$ShouldSucceed,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    $output = (& $script:HelmExecutable @Arguments 2>&1) -join "`n"
    $exitCode = $LASTEXITCODE
    if ($ShouldSucceed -and $exitCode -ne 0) {
        throw "Helm a refusé le scénario '$Scenario' : $output"
    }
    if (-not $ShouldSucceed -and $exitCode -eq 0) {
        throw "Helm a accepté le scénario interdit '$Scenario'."
    }
    return $output
}

function Get-Documents {
    param([Parameter(Mandatory = $true)][string]$Rendered)

    return @($Rendered -split '(?m)^---\s*$' | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    })
}

function Get-DocumentsByKind {
    param(
        [Parameter(Mandatory = $true)][string]$Rendered,
        [Parameter(Mandatory = $true)][string]$Kind
    )

    return @(Get-Documents -Rendered $Rendered | Where-Object {
        $_ -match "(?m)^kind: $([regex]::Escape($Kind))\s*$"
    })
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}
else {
    $RepositoryPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
}
$chartPath = (Resolve-Path (
    Join-Path $RepositoryPath "charts\subnetory"
)).Path

$helmCommands = @(Get-Command `
    -Name $HelmPath `
    -CommandType Application `
    -ErrorAction Stop)
$helmCommand = @($helmCommands | Where-Object {
    [System.IO.Path]::GetFileName($_.Source) -in @("helm", "helm.exe")
} | Select-Object -First 1)[0]
if ($null -eq $helmCommand) {
    throw "L'exécutable Helm doit être helm ou helm.exe."
}
if ([System.IO.Path]::GetFileName($helmCommand.Source) -notin @("helm", "helm.exe")) {
    throw "L'exécutable Helm doit être helm ou helm.exe."
}
$script:HelmExecutable = $helmCommand.Source

& $script:HelmExecutable lint $chartPath --strict
if ($LASTEXITCODE -ne 0) {
    throw "Le chart Helm ne passe pas helm lint --strict."
}

$baseArguments = @(
    "template", "subnetory", $chartPath,
    "--namespace", "subnetory"
)
$renderedDefault = Invoke-HelmTemplate `
    -Arguments $baseArguments `
    -ShouldSucceed $true `
    -Scenario "sauvegarde désactivée par défaut"
if (
    @(Get-DocumentsByKind -Rendered $renderedDefault -Kind "CronJob").Count -ne 0 -or
    $renderedDefault -match '(?m)^\s*app\.kubernetes\.io/component: backup\s*$'
) {
    throw "Des ressources de sauvegarde sont actives par défaut."
}

$renderedDaily = Invoke-HelmTemplate `
    -Arguments ($baseArguments + @("--set", "backup.enabled=true")) `
    -ShouldSucceed $true `
    -Scenario "sauvegarde quotidienne"
$dailyCronJobs = @(Get-DocumentsByKind -Rendered $renderedDaily -Kind "CronJob")
$backupPvcs = @(Get-DocumentsByKind -Rendered $renderedDaily -Kind "PersistentVolumeClaim" |
    Where-Object {
        $_ -match '(?m)^\s*app\.kubernetes\.io/component: backup\s*$'
    })
$backupNetworkPolicies = @(Get-DocumentsByKind `
    -Rendered $renderedDaily `
    -Kind "NetworkPolicy" | Where-Object {
        $_ -match '(?m)^\s*app\.kubernetes\.io/component: backup\s*$'
    })
if (
    $dailyCronJobs.Count -ne 1 -or
    $backupPvcs.Count -ne 1 -or
    $backupNetworkPolicies.Count -ne 1
) {
    throw "Le profil quotidien doit rendre un CronJob, un PVC et sa NetworkPolicy."
}
$dailyCronJob = $dailyCronJobs[0]
foreach ($requiredPattern in @(
    '(?m)^\s*name: subnetory-backups-daily\s*$',
    '(?m)^\s*automountServiceAccountToken: false\s*$',
    '(?m)^\s*hostNetwork: false\s*$',
    '(?m)^\s*hostPID: false\s*$',
    '(?m)^\s*hostIPC: false\s*$',
    '(?m)^\s*shareProcessNamespace: false\s*$',
    '(?m)^\s*allowPrivilegeEscalation: false\s*$',
    '(?m)^\s*readOnlyRootFilesystem: true\s*$',
    '(?m)^\s*- ALL\s*$',
    '(?m)^\s*value: /run/secrets/postgres-password\s*$'
)) {
    if ($dailyCronJob -notmatch $requiredPattern) {
        throw "Protection absente du CronJob quotidien : $requiredPattern"
    }
}
if (
    $dailyCronJob -match '(?m)^\s*serviceAccountName:' -or
    $dailyCronJob -match '(?m)^\s*- name: (?:PGPASSWORD|POSTGRES_PASSWORD)\s*$' -or
    $dailyCronJob -match '(?m)^\s*valueFrom:'
) {
    throw "Le CronJob quotidien contient un accès ou un secret interdit."
}
$backupNetworkPolicy = $backupNetworkPolicies[0]
foreach ($requiredPattern in @(
    '(?m)^\s*ingress: \[\]\s*$',
    '(?m)^\s*kubernetes\.io/metadata\.name: kube-system\s*$',
    '(?m)^\s*k8s-app: kube-dns\s*$',
    '(?m)^\s*app\.kubernetes\.io/component: postgresql\s*$'
)) {
    if ($backupNetworkPolicy -notmatch $requiredPattern) {
        throw "Restriction absente de la NetworkPolicy backup : $requiredPattern"
    }
}

$renderedWithGlobalNetworkPolicy = Invoke-HelmTemplate `
    -Arguments ($baseArguments + @(
        "--set", "backup.enabled=true",
        "--set", "networkPolicy.enabled=true"
    )) `
    -ShouldSucceed $true `
    -Scenario "sauvegarde avec NetworkPolicy globale"
$postgresNetworkPolicies = @(Get-DocumentsByKind `
    -Rendered $renderedWithGlobalNetworkPolicy `
    -Kind "NetworkPolicy" | Where-Object {
        $_ -match (
            '(?ms)^kind: NetworkPolicy\s+metadata:.*?' +
            '^\s*app\.kubernetes\.io/component: postgresql\s*^spec:'
        )
    })
if (
    $postgresNetworkPolicies.Count -ne 1 -or
    $postgresNetworkPolicies[0] -notmatch (
        '(?ms)podSelector:\s*\r?\n\s*matchLabels:.*?' +
        'app\.kubernetes\.io/component: backup'
    )
) {
    throw "La NetworkPolicy PostgreSQL n'autorise pas explicitement les pods backup."
}

$allScheduleArguments = $baseArguments + @(
    "--set", "backup.enabled=true",
    "--set", "backup.schedules.hourly.enabled=true",
    "--set", "backup.schedules.daily.enabled=true",
    "--set", "backup.schedules.monthly.enabled=true",
    "--set", "backup.schedules.quarterly.enabled=true"
)
$renderedAll = Invoke-HelmTemplate `
    -Arguments $allScheduleArguments `
    -ShouldSucceed $true `
    -Scenario "quatre niveaux de sauvegarde"
$allCronJobs = @(Get-DocumentsByKind -Rendered $renderedAll -Kind "CronJob")
$cronJobNames = @($allCronJobs | ForEach-Object {
    $match = [regex]::Match($_, '(?m)^\s*name: ([a-z0-9-]+)\s*$')
    if (-not $match.Success) {
        throw "Nom de CronJob introuvable."
    }
    $match.Groups[1].Value
})
if (
    $allCronJobs.Count -ne 4 -or
    @($cronJobNames | Sort-Object -Unique).Count -ne 4
) {
    throw "Les quatre niveaux de sauvegarde n'ont pas des noms uniques."
}

$longName = "subnetory-platform-security-validation-with-a-very-long-name"
$renderedLongNames = Invoke-HelmTemplate `
    -Arguments ($allScheduleArguments + @(
        "--set-string", "fullnameOverride=$longName"
    )) `
    -ShouldSucceed $true `
    -Scenario "noms Kubernetes longs"
$longCronJobs = @(Get-DocumentsByKind -Rendered $renderedLongNames -Kind "CronJob")
$longNames = @($longCronJobs | ForEach-Object {
    [regex]::Match($_, '(?m)^\s*name: ([a-z0-9-]+)\s*$').Groups[1].Value
})
if (
    $longNames.Count -ne 4 -or
    @($longNames | Sort-Object -Unique).Count -ne 4 -or
    @($longNames | Where-Object { $_.Length -gt 63 }).Count -ne 0
) {
    throw "Les noms longs des CronJobs sont invalides ou ambigus."
}

$existingClaim = "subnetory-preprovisioned-backups"
$renderedExistingClaim = Invoke-HelmTemplate `
    -Arguments ($baseArguments + @(
        "--set", "backup.enabled=true",
        "--set-string", "backup.persistence.existingClaim=$existingClaim"
    )) `
    -ShouldSucceed $true `
    -Scenario "PVC préexistant"
$existingBackupPvcs = @(Get-DocumentsByKind `
    -Rendered $renderedExistingClaim `
    -Kind "PersistentVolumeClaim" | Where-Object {
        $_ -match '(?m)^\s*app\.kubernetes\.io/component: backup\s*$'
    })
if (
    $existingBackupPvcs.Count -ne 0 -or
    $renderedExistingClaim -notmatch (
        '(?m)^\s*claimName: ' + [regex]::Escape($existingClaim) + '\s*$'
    )
) {
    throw "Le chemin avec PVC préexistant est incorrect."
}

$invalidScenarios = @(
    @(
        "sauvegarde d'une base externe",
        "--set", "backup.enabled=true",
        "--set", "postgresql.enabled=false",
        "--set", "externalDatabase.enabled=true",
        "--set", "externalDatabase.host=postgres.example.internal"
    ),
    @(
        "aucun planning actif",
        "--set", "backup.enabled=true",
        "--set", "backup.schedules.hourly.enabled=false",
        "--set", "backup.schedules.daily.enabled=false",
        "--set", "backup.schedules.monthly.enabled=false",
        "--set", "backup.schedules.quarterly.enabled=false"
    ),
    @(
        "fuseau contenant une injection",
        "--set", "backup.enabled=true",
        "--set-string", "backup.timeZone=Etc/UTC;whoami"
    ),
    @(
        "backoff excessif",
        "--set", "backup.enabled=true",
        "--set", "backup.job.backoffLimit=7"
    ),
    @(
        "délai actif insuffisant",
        "--set", "backup.enabled=true",
        "--set", "backup.job.activeDeadlineSeconds=59"
    ),
    @(
        "backup et backupApp actifs simultanément",
        "--set", "backup.enabled=true",
        "--set", "backupApp.enabled=true"
    )
)
foreach ($scenario in $invalidScenarios) {
    $name = $scenario[0]
    $values = @($scenario | Select-Object -Skip 1)
    $null = Invoke-HelmTemplate `
        -Arguments ($baseArguments + $values) `
        -ShouldSucceed $false `
        -Scenario $name
}

foreach ($rendered in @($renderedDefault, $renderedDaily, $renderedAll)) {
    if (
        $rendered -match '(?m)^kind: Secret\s*$' -or
        $rendered -match '(?m)^\s*- name: (?:SUBNETORY_JWT_SECRET|SUBNETORY_ADMIN_DEFAULT_PASSWORD|SPRING_DATASOURCE_PASSWORD|PGPASSWORD|POSTGRES_PASSWORD)\s*$'
    ) {
        throw "Un manifeste rendu expose une ressource ou variable secrète interdite."
    }
}

Write-Host "HELM_BACKUP_DEFAULT_DISABLED=PASS"
Write-Host "HELM_BACKUP_SCHEDULES_UNIQUE=PASS"
Write-Host "HELM_BACKUP_PVC_MODES=PASS"
Write-Host "HELM_BACKUP_SECURITY_CONTEXT=PASS"
Write-Host "HELM_BACKUP_NETWORK_POLICY=PASS"
Write-Host "HELM_BACKUP_INVALID_VALUES_REJECTED=PASS"
Write-Host "HELM_BACKUP_NO_CLEAR_TEXT_SECRET=PASS"
Write-Host "HELM_BACKUP_MUTUAL_EXCLUSION=PASS"
exit 0
