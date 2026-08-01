param(
    [string]$BackupRoot = "",
    [int]$WarningHours = 30,
    [int]$CriticalHours = 48,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

function New-BackupStatus {
    param(
        [string]$Status,
        [string]$Message,
        [string]$BackupRoot,
        [string]$LatestBackupPath = "",
        [Nullable[double]]$LatestBackupAgeHours = $null,
        [Nullable[long]]$LatestBackupSizeBytes = $null,
        [string]$LatestBackupSha256 = "",
        [Nullable[long]]$LatestBackupDecompressedSizeBytes = $null,
        [Nullable[bool]]$LatestBackupGzipValid = $null,
        [int]$BackupCount = 0,
        [int]$WarningHours = 30,
        [int]$CriticalHours = 48
    )

    [PSCustomObject]@{
        status = $Status
        message = $Message
        backupRoot = $BackupRoot
        latestBackupPath = $LatestBackupPath
        latestBackupAgeHours = $LatestBackupAgeHours
        latestBackupSizeBytes = $LatestBackupSizeBytes
        latestBackupSha256 = $LatestBackupSha256
        latestBackupDecompressedSizeBytes = $LatestBackupDecompressedSizeBytes
        latestBackupGzipValid = $LatestBackupGzipValid
        backupCount = $BackupCount
        warningHours = $WarningHours
        criticalHours = $CriticalHours
        checkedAt = (Get-Date).ToString("s")
    }
}

function Write-BackupStatus {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Result,
        [switch]$Json
    )

    if ($Json) {
        $Result | ConvertTo-Json -Depth 4
        return
    }

    Write-Host ("Status               : {0}" -f $Result.status)
    Write-Host ("Message              : {0}" -f $Result.message)
    Write-Host ("Backup root          : {0}" -f $Result.backupRoot)
    Write-Host ("Backup count         : {0}" -f $Result.backupCount)

    if (-not [string]::IsNullOrWhiteSpace($Result.latestBackupPath)) {
        Write-Host ("Latest backup        : {0}" -f $Result.latestBackupPath)
        Write-Host ("Latest age hours     : {0}" -f $Result.latestBackupAgeHours)
        Write-Host ("Latest size bytes    : {0}" -f $Result.latestBackupSizeBytes)

        if (-not [string]::IsNullOrWhiteSpace($Result.latestBackupSha256)) {
            Write-Host ("Latest SHA256        : {0}" -f $Result.latestBackupSha256)
        }

        if ($null -ne $Result.latestBackupDecompressedSizeBytes) {
            Write-Host ("Decompressed bytes   : {0}" -f $Result.latestBackupDecompressedSizeBytes)
        }

        if ($null -ne $Result.latestBackupGzipValid) {
            Write-Host ("GZip integrity       : {0}" -f $Result.latestBackupGzipValid)
        }
    }

    Write-Host ("Warning threshold    : {0} hours" -f $Result.warningHours)
    Write-Host ("Critical threshold   : {0} hours" -f $Result.criticalHours)
    Write-Host ("Checked at           : {0}" -f $Result.checkedAt)
}

function Test-GzipBackupIntegrity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fileStream = $null
    $gzipStream = $null
    $totalBytes = [int64]0

    try {
        $fileStream = [System.IO.File]::OpenRead($Path)
        $gzipStream = New-Object System.IO.Compression.GzipStream -ArgumentList $fileStream, ([System.IO.Compression.CompressionMode]::Decompress)
        $buffer = New-Object byte[] 81920

        do {
            $read = $gzipStream.Read($buffer, 0, $buffer.Length)
            $totalBytes += [int64]$read
        } while ($read -gt 0)

        [PSCustomObject]@{
            isValid = $true
            decompressedSizeBytes = $totalBytes
            errorMessage = ""
        }
    }
    catch {
        [PSCustomObject]@{
            isValid = $false
            decompressedSizeBytes = $totalBytes
            errorMessage = $_.Exception.Message
        }
    }
    finally {
        if ($null -ne $gzipStream) {
            $gzipStream.Dispose()
        }

        if ($null -ne $fileStream) {
            $fileStream.Dispose()
        }
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($BackupRoot)) {
        # Le script est dans backend/scripts/.
        # Le dossier backups/ par defaut est a la racine du repository,
        # comme backup-postgres.ps1 : backend/scripts/../../backups.
        $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        $BackupRoot = Join-Path $repoRoot "backups"
    }

    $BackupRoot = [System.IO.Path]::GetFullPath($BackupRoot)

    if ($WarningHours -le 0) {
        throw "WarningHours must be greater than 0."
    }

    if ($CriticalHours -le 0) {
        throw "CriticalHours must be greater than 0."
    }

    if ($WarningHours -ge $CriticalHours) {
        throw "WarningHours must be lower than CriticalHours."
    }

    if (-not (Test-Path -Path $BackupRoot -PathType Container)) {
        $result = New-BackupStatus -Status "CRITICAL" -Message "Backup directory does not exist." -BackupRoot $BackupRoot -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    $backupFiles = Get-ChildItem -Path $BackupRoot -Recurse -File -Filter "*.sql.gz" | Sort-Object LastWriteTime -Descending

    if (-not $backupFiles -or $backupFiles.Count -eq 0) {
        $result = New-BackupStatus -Status "CRITICAL" -Message "No .sql.gz backup file found." -BackupRoot $BackupRoot -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    $latestBackup = $backupFiles | Select-Object -First 1
    $age = (Get-Date) - $latestBackup.LastWriteTime
    $ageHours = [Math]::Round($age.TotalHours, 2)

    if ($latestBackup.Length -le 0) {
        $result = New-BackupStatus -Status "CRITICAL" -Message "Latest backup file is empty." -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    $sha256 = (Get-FileHash -Path $latestBackup.FullName -Algorithm SHA256).Hash
    $integrity = Test-GzipBackupIntegrity -Path $latestBackup.FullName

    if (-not $integrity.isValid) {
        $message = "Latest backup gzip integrity check failed: {0}" -f $integrity.errorMessage
        $result = New-BackupStatus -Status "CRITICAL" -Message $message -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -LatestBackupSha256 $sha256 -LatestBackupDecompressedSizeBytes $integrity.decompressedSizeBytes -LatestBackupGzipValid $false -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    if ($integrity.decompressedSizeBytes -le 0) {
        $result = New-BackupStatus -Status "CRITICAL" -Message "Latest backup decompressed content is empty." -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -LatestBackupSha256 $sha256 -LatestBackupDecompressedSizeBytes $integrity.decompressedSizeBytes -LatestBackupGzipValid $true -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    if ($age.TotalHours -ge $CriticalHours) {
        $result = New-BackupStatus -Status "CRITICAL" -Message "Latest backup is older than the critical threshold." -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -LatestBackupSha256 $sha256 -LatestBackupDecompressedSizeBytes $integrity.decompressedSizeBytes -LatestBackupGzipValid $true -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 2
    }

    if ($age.TotalHours -ge $WarningHours) {
        $result = New-BackupStatus -Status "WARNING" -Message "Latest backup is older than the warning threshold." -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -LatestBackupSha256 $sha256 -LatestBackupDecompressedSizeBytes $integrity.decompressedSizeBytes -LatestBackupGzipValid $true -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
        Write-BackupStatus -Result $result -Json:$Json
        exit 1
    }

    $result = New-BackupStatus -Status "OK" -Message "Latest backup is recent and gzip integrity is valid." -BackupRoot $BackupRoot -LatestBackupPath $latestBackup.FullName -LatestBackupAgeHours $ageHours -LatestBackupSizeBytes $latestBackup.Length -LatestBackupSha256 $sha256 -LatestBackupDecompressedSizeBytes $integrity.decompressedSizeBytes -LatestBackupGzipValid $true -BackupCount $backupFiles.Count -WarningHours $WarningHours -CriticalHours $CriticalHours
    Write-BackupStatus -Result $result -Json:$Json
    exit 0
}
catch {
    $message = "Backup check failed: {0}" -f $_.Exception.Message
    $result = New-BackupStatus -Status "UNKNOWN" -Message $message -BackupRoot $BackupRoot -WarningHours $WarningHours -CriticalHours $CriticalHours
    Write-BackupStatus -Result $result -Json:$Json
    exit 3
}
