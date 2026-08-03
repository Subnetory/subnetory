#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("hourly", "daily", "monthly", "quarterly")]
    [string]$BackupLevel,
    [Parameter(Mandatory = $true)]
    [ValidatePattern(
        '^subnetory-(hourly|daily|monthly|quarterly)-[0-9]{8}T[0-9]{6}Z\.dump\.gz$'
    )]
    [string]$BackupFile,
    [ValidateLength(1, 63)]
    [ValidatePattern('^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$')]
    [string]$Namespace = "subnetory",
    [ValidateLength(1, 53)]
    [ValidatePattern('^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$')]
    [string]$ReleaseName = "subnetory",
    [ValidateRange(0, 2147483647)]
    [int]$WitnessId = 0,
    [AllowEmptyString()]
    [ValidatePattern('^$|^[A-Fa-f0-9]{64}$')]
    [string]$WitnessExpectedSha256 = "",
    [ValidateRange(60, 1800)]
    [int]$TimeoutSeconds = 600,
    [string]$Kubectl = "kubectl"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$requiredPostgresImage = "postgres:17.10-alpine3.23"
$allowedDnsLabelPattern = '^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$'
$allowedClaimPattern = (
    '^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?' +
    '(?:\.[a-z0-9](?:[-a-z0-9]*[a-z0-9])?)*$'
)
$expectedBackupPattern = (
    '^subnetory-' + [regex]::Escape($BackupLevel) +
    '-[0-9]{8}T[0-9]{6}Z\.dump\.gz$'
)
if ($BackupFile -notmatch $expectedBackupPattern) {
    throw "Le fichier de sauvegarde ne correspond pas au niveau demandé."
}

$witnessEnabled = -not [string]::IsNullOrWhiteSpace($WitnessExpectedSha256)
if ($witnessEnabled -and $WitnessId -lt 1) {
    throw "WitnessId doit être positif lorsque la vérification témoin est activée."
}
if (-not $witnessEnabled -and $WitnessId -ne 0) {
    throw "WitnessId doit rester à zéro lorsque la vérification témoin est désactivée."
}

$kubectlCommands = @(Get-Command `
    -Name $Kubectl `
    -CommandType Application `
    -ErrorAction Stop)
$kubectlCommand = @($kubectlCommands | Where-Object {
    [System.IO.Path]::GetFileName($_.Source) -in @("kubectl", "kubectl.exe")
} | Select-Object -First 1)[0]
if ($null -eq $kubectlCommand) {
    throw "L'exécutable Kubernetes doit être kubectl ou kubectl.exe."
}
$kubectlLeaf = [System.IO.Path]::GetFileName($kubectlCommand.Source)
if ($kubectlLeaf -notin @("kubectl", "kubectl.exe")) {
    throw "L'exécutable Kubernetes doit être kubectl ou kubectl.exe."
}
$kubectlPath = $kubectlCommand.Source

$restoreScriptPath = (
    Resolve-Path (
        Join-Path $PSScriptRoot "..\charts\subnetory\files\restore-drill.sh"
    )
).Path
$restoreScript = [System.IO.File]::ReadAllText($restoreScriptPath)
if ([string]::IsNullOrWhiteSpace($restoreScript)) {
    throw "Le script de restore drill est vide."
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

function Invoke-KubectlJson {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = (& $kubectlPath @Arguments 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl a échoué : $output"
    }
    return $output | ConvertFrom-Json
}

function New-KubernetesResource {
    param([Parameter(Mandatory = $true)][hashtable]$Resource)

    $json = $Resource | ConvertTo-Json -Depth 30 -Compress
    $output = ($json | & $kubectlPath create --filename - 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Création Kubernetes refusée : $output"
    }
}

function Remove-KubernetesResource {
    param(
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $output = (& $kubectlPath delete $Kind $Name `
        --namespace $Namespace `
        --ignore-not-found=true `
        --wait=true 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        return "$Kind/$Name : $output"
    }
    return ""
}

$null = Invoke-KubectlJson -Arguments @(
    "get", "namespace", $Namespace, "--output", "json"
)

$selector = (
    "app.kubernetes.io/instance=$ReleaseName," +
    "app.kubernetes.io/component=backup," +
    "subnetory.io/backup-level=$BackupLevel"
)
$cronJobs = Invoke-KubectlJson -Arguments @(
    "get", "cronjobs", "--namespace", $Namespace,
    "--selector", $selector, "--output", "json"
)
if (@($cronJobs.items).Count -ne 1) {
    throw "Un unique CronJob de sauvegarde est requis pour le niveau demandé."
}

$cronJob = $cronJobs.items[0]
$cronPodSpec = $cronJob.spec.jobTemplate.spec.template.spec
if ($cronPodSpec.automountServiceAccountToken -ne $false) {
    throw "Le CronJob source autorise un token Kubernetes."
}
if (@($cronPodSpec.containers).Count -ne 1) {
    throw "Le CronJob source doit contenir un unique conteneur."
}
$cronContainer = $cronPodSpec.containers[0]
if ($cronContainer.image -ne $requiredPostgresImage) {
    throw "Image PostgreSQL non approuvée : '$($cronContainer.image)'."
}
if (@($cronContainer.env | Where-Object {
    $_.name -in @("PGPASSWORD", "POSTGRES_PASSWORD") -or
    $_.PSObject.Properties.Name -contains "valueFrom"
}).Count -ne 0) {
    throw "Le CronJob source expose un secret par variable d'environnement."
}

$backupVolume = @($cronPodSpec.volumes | Where-Object {
    $_.name -eq "backup-storage" -and
    $_.PSObject.Properties.Name -contains "persistentVolumeClaim"
})
if ($backupVolume.Count -ne 1) {
    throw "Le PVC de sauvegarde du CronJob est introuvable."
}
$backupClaim = [string]$backupVolume[0].persistentVolumeClaim.claimName
if (
    $backupClaim.Length -lt 1 -or
    $backupClaim.Length -gt 253 -or
    $backupClaim -notmatch $allowedClaimPattern
) {
    throw "Le nom du PVC de sauvegarde n'est pas sûr."
}

$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$resourceName = "subnetory-restore-drill-$runId"
$scriptName = "$resourceName-script"
$expectedFlywayVersion = Get-ExpectedFlywayVersion
$labels = @{
    "app.kubernetes.io/name" = "subnetory"
    "app.kubernetes.io/instance" = $ReleaseName
    "app.kubernetes.io/component" = "restore-drill"
    "subnetory.io/restore-drill-id" = $runId
}
$podLabels = @{}
foreach ($entry in $labels.GetEnumerator()) {
    $podLabels[$entry.Key] = $entry.Value
}

$configMap = @{
    apiVersion = "v1"
    kind = "ConfigMap"
    metadata = @{
        name = $scriptName
        namespace = $Namespace
        labels = $labels
    }
    data = @{
        "restore-drill.sh" = $restoreScript
    }
}
$networkPolicy = @{
    apiVersion = "networking.k8s.io/v1"
    kind = "NetworkPolicy"
    metadata = @{
        name = $resourceName
        namespace = $Namespace
        labels = $labels
    }
    spec = @{
        podSelector = @{
            matchLabels = @{
                "subnetory.io/restore-drill-id" = $runId
            }
        }
        policyTypes = @("Ingress", "Egress")
        ingress = @()
        egress = @()
    }
}
$job = @{
    apiVersion = "batch/v1"
    kind = "Job"
    metadata = @{
        name = $resourceName
        namespace = $Namespace
        labels = $labels
    }
    spec = @{
        backoffLimit = 0
        activeDeadlineSeconds = $TimeoutSeconds
        ttlSecondsAfterFinished = 300
        template = @{
            metadata = @{ labels = $podLabels }
            spec = @{
                automountServiceAccountToken = $false
                enableServiceLinks = $false
                hostNetwork = $false
                hostPID = $false
                hostIPC = $false
                shareProcessNamespace = $false
                restartPolicy = "Never"
                terminationGracePeriodSeconds = 30
                dnsPolicy = "None"
                dnsConfig = @{
                    nameservers = @("127.0.0.1")
                    options = @(@{ name = "ndots"; value = "1" })
                }
                securityContext = @{
                    runAsNonRoot = $true
                    runAsUser = 70
                    runAsGroup = 70
                    fsGroup = 70
                    fsGroupChangePolicy = "OnRootMismatch"
                    seccompProfile = @{ type = "RuntimeDefault" }
                }
                containers = @(@{
                    name = "restore-drill"
                    image = $requiredPostgresImage
                    imagePullPolicy = "IfNotPresent"
                    command = @("/bin/sh", "/opt/subnetory-restore/restore-drill.sh")
                    securityContext = @{
                        allowPrivilegeEscalation = $false
                        readOnlyRootFilesystem = $true
                        capabilities = @{ drop = @("ALL") }
                    }
                    env = @(
                        @{ name = "BACKUP_LEVEL"; value = $BackupLevel },
                        @{ name = "BACKUP_FILE"; value = $BackupFile },
                        @{
                            name = "WITNESS_ENABLED"
                            value = $(if ($witnessEnabled) { "true" } else { "false" })
                        },
                        @{ name = "WITNESS_ID"; value = [string]$WitnessId },
                        @{
                            name = "WITNESS_EXPECTED_SHA256"
                            value = $(
                                if ($witnessEnabled) {
                                    $WitnessExpectedSha256.ToLowerInvariant()
                                }
                                else {
                                    "disabled"
                                }
                            )
                        },
                        @{
                            name = "EXPECTED_FLYWAY_VERSION"
                            value = $expectedFlywayVersion
                        }
                    )
                    resources = @{
                        requests = @{
                            cpu = "100m"
                            memory = "128Mi"
                            "ephemeral-storage" = "256Mi"
                        }
                        limits = @{
                            cpu = "1"
                            memory = "1Gi"
                            "ephemeral-storage" = "20Gi"
                        }
                    }
                    volumeMounts = @(
                        @{
                            name = "backup-storage"
                            mountPath = "/backups"
                            readOnly = $true
                        },
                        @{
                            name = "restore-script"
                            mountPath = "/opt/subnetory-restore"
                            readOnly = $true
                        },
                        @{
                            name = "restore-data"
                            mountPath = "/var/lib/postgresql/data"
                        },
                        @{ name = "temporary-directory"; mountPath = "/tmp" },
                        @{
                            name = "runtime-directory"
                            mountPath = "/var/run/postgresql"
                        }
                    )
                })
                volumes = @(
                    @{
                        name = "backup-storage"
                        persistentVolumeClaim = @{
                            claimName = $backupClaim
                            readOnly = $true
                        }
                    },
                    @{
                        name = "restore-script"
                        configMap = @{
                            name = $scriptName
                            defaultMode = 365
                        }
                    },
                    @{
                        name = "restore-data"
                        emptyDir = @{ sizeLimit = "20Gi" }
                    },
                    @{
                        name = "temporary-directory"
                        emptyDir = @{ sizeLimit = "256Mi" }
                    },
                    @{
                        name = "runtime-directory"
                        emptyDir = @{ sizeLimit = "16Mi" }
                    }
                )
            }
        }
    }
}

$serializedJob = $job | ConvertTo-Json -Depth 30 -Compress
if (
    $serializedJob -match 'secretKeyRef|secretRef|hostPath' -or
    $serializedJob -match 'PGPASSWORD|POSTGRES_PASSWORD'
) {
    throw "Le Job généré contient une source de secret ou un montage interdit."
}
if ($resourceName -notmatch $allowedDnsLabelPattern) {
    throw "Le nom du Job généré n'est pas sûr."
}

$configMapCreated = $false
$networkPolicyCreated = $false
$jobCreated = $false
$cleanupFailures = [System.Collections.Generic.List[string]]::new()
try {
    New-KubernetesResource -Resource $configMap
    $configMapCreated = $true
    New-KubernetesResource -Resource $networkPolicy
    $networkPolicyCreated = $true
    New-KubernetesResource -Resource $job
    $jobCreated = $true

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds + 30)
    $completed = $false
    $lastPollError = $null
    do {
        # kubectl / l'API Kubernetes peuvent répondre de façon transitoire
        # (sortie vide, Job pas encore répliqué dans l'API server juste après
        # sa création, hoquet réseau) : ce n'est pas une preuve d'échec du
        # restore drill lui-même, seulement une non-disponibilité passagère
        # de l'information de statut. On tolère ces ratés dans la boucle
        # d'attente, exactement comme "Job pas encore terminé", jusqu'à la
        # même échéance ; seule une non-disponibilité persistante jusqu'au
        # délai fait échouer le restore drill (message ci-dessous).
        try {
            $jobStatus = Invoke-KubectlJson -Arguments @(
                "get", "job", $resourceName,
                "--namespace", $Namespace,
                "--output", "json"
            )
            $conditions = @()
            if (
                $null -ne $jobStatus -and
                @($jobStatus.PSObject.Properties.Name) -contains "status" -and
                $null -ne $jobStatus.status -and
                @($jobStatus.status.PSObject.Properties.Name) -contains "conditions"
            ) {
                $conditions = @($jobStatus.status.conditions)
            }
            $lastPollError = $null
        } catch {
            $conditions = @()
            $lastPollError = $_.Exception.Message
        }
        if (@($conditions | Where-Object {
            $_.type -eq "Failed" -and $_.status -eq "True"
        }).Count -gt 0) {
            break
        }
        $completed = @($conditions | Where-Object {
            $_.type -eq "Complete" -and $_.status -eq "True"
        }).Count -gt 0
        if (-not $completed) {
            Start-Sleep -Seconds 2
        }
    } while (-not $completed -and [DateTimeOffset]::UtcNow -lt $deadline)
    if (-not $completed -and $null -ne $lastPollError) {
        throw "Statut du Job de restauration indisponible de façon persistante : $lastPollError"
    }

    $pods = Invoke-KubectlJson -Arguments @(
        "get", "pods", "--namespace", $Namespace,
        "--selector", "job-name=$resourceName", "--output", "json"
    )
    if (@($pods.items).Count -ne 1) {
        throw "Un unique pod de restore drill est attendu."
    }
    $pod = $pods.items[0]
    $enabledHostNamespaces = @(
        "hostNetwork",
        "hostPID",
        "hostIPC",
        "shareProcessNamespace"
    ) | Where-Object {
        $pod.spec.PSObject.Properties.Name -contains $_ -and
        $pod.spec.$_ -ne $false
    }
    if (
        $pod.spec.automountServiceAccountToken -ne $false -or
        $pod.spec.dnsPolicy -ne "None" -or
        @($enabledHostNamespaces).Count -ne 0 -or
        @($pod.spec.volumes | Where-Object {
            $_.name -like "kube-api-access-*" -or
            $_.PSObject.Properties.Name -contains "secret" -or
            $_.PSObject.Properties.Name -contains "hostPath"
        }).Count -ne 0
    ) {
        throw "Le pod de restore drill contient un accès interdit."
    }

    $logs = (& $kubectlPath logs $pod.metadata.name `
        --namespace $Namespace 2>&1) -join "`n"
    if (-not $completed) {
        throw "Le restore drill a échoué ou expiré : $logs"
    }
    if ($logs -notmatch "Restore drill completed:") {
        throw "Le restore drill n'a pas produit la confirmation attendue : $logs"
    }

    Write-Output "RESTORE_DRILL_ISOLATED=PASS"
    Write-Output "RESTORE_DRILL_FLYWAY=PASS"
    if ($witnessEnabled) {
        Write-Output "RESTORE_DRILL_WITNESS=PASS"
    }
}
finally {
    if ($jobCreated) {
        $cleanupResult = Remove-KubernetesResource -Kind "job" -Name $resourceName
        if (-not [string]::IsNullOrWhiteSpace($cleanupResult)) {
            $cleanupFailures.Add($cleanupResult)
        }
    }
    if ($networkPolicyCreated) {
        $cleanupResult = Remove-KubernetesResource `
            -Kind "networkpolicy" `
            -Name $resourceName
        if (-not [string]::IsNullOrWhiteSpace($cleanupResult)) {
            $cleanupFailures.Add($cleanupResult)
        }
    }
    if ($configMapCreated) {
        $cleanupResult = Remove-KubernetesResource `
            -Kind "configmap" `
            -Name $scriptName
        if (-not [string]::IsNullOrWhiteSpace($cleanupResult)) {
            $cleanupFailures.Add($cleanupResult)
        }
    }
    if ($cleanupFailures.Count -gt 0) {
        throw (
            "Nettoyage incomplet du restore drill : " +
            ($cleanupFailures -join "; ")
        )
    }
}
