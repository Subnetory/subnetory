#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$RepositoryPath,
    [string]$RedactedLogPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:SecretValues = @()
$script:ComposeStarted = $false
$script:InspectContainer = $null
$script:AppImageId = $null
$script:MainFailure = $null
$script:LogLeaks = [System.Collections.Generic.List[string]]::new()
$script:CleanupErrors = [System.Collections.Generic.List[string]]::new()

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

    $Protected = $Text
    foreach ($Secret in $script:SecretValues) {
        if (-not [string]::IsNullOrEmpty($Secret)) {
            $Protected = $Protected.Replace($Secret, "[REDACTED]")
        }
    }

    return $Protected
}


function Test-ComposePortOutputHasPublishedBinding {
    param(
        [AllowNull()]
        [string]$Text
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }

    foreach ($Line in @($Text -split "`r?`n")) {
        $Candidate = $Line.Trim()

        if ([string]::IsNullOrWhiteSpace($Candidate)) {
            continue
        }

        $PortMatch = [regex]::Match(
            $Candidate,
            ':(?<port>\d+)$',
            [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
        )

        if (-not $PortMatch.Success) {
            continue
        }

        [int]$PublishedPort = 0

        if (
            [int]::TryParse(
                $PortMatch.Groups["port"].Value,
                [ref]$PublishedPort
            ) -and
            $PublishedPort -ge 1 -and
            $PublishedPort -le 65535
        ) {
            return $true
        }
    }

    return $false
}

function Assert-ComposePortParserSelfTest {
    $Cases = @(
        [PSCustomObject]@{
            Name     = "sentinelle Compose"
            Text     = "invalid IP:0"
            Expected = $false
        }
        [PSCustomObject]@{
            Name     = "adresse absente, port zéro"
            Text     = ":0"
            Expected = $false
        }
        [PSCustomObject]@{
            Name     = "IPv4, port zéro"
            Text     = "0.0.0.0:0"
            Expected = $false
        }
        [PSCustomObject]@{
            Name     = "publication IPv4"
            Text     = "0.0.0.0:54321"
            Expected = $true
        }
        [PSCustomObject]@{
            Name     = "publication IPv6"
            Text     = "[::]:49153"
            Expected = $true
        }
    )

    foreach ($Case in $Cases) {
        $Actual = Test-ComposePortOutputHasPublishedBinding `
            -Text $Case.Text

        if ($Actual -ne $Case.Expected) {
            throw (
                "Auto-test du parseur de ports en échec." +
                "`nCas      : $($Case.Name)" +
                "`nEntrée   : $($Case.Text)" +
                "`nAttendu  : $($Case.Expected)" +
                "`nObtenu   : $Actual"
            )
        }
    }

    Write-Host "Auto-test ports  : 5 cas validés"
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FileName,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [string]$WorkingDirectory,

        [switch]$AllowFailure,

        [int]$TimeoutSeconds = 1800
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
        throw "Impossible de démarrer $FileName."
    }

    $StdoutTask = $Process.StandardOutput.ReadToEndAsync()
    $StderrTask = $Process.StandardError.ReadToEndAsync()

    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        try {
            $Process.Kill($true)
        }
        catch {
        }

        throw (
            "Délai dépassé : $FileName $($Arguments -join ' ')" +
            "`nTimeout : $TimeoutSeconds secondes"
        )
    }

    $Stdout = $StdoutTask.GetAwaiter().GetResult()
    $Stderr = $StderrTask.GetAwaiter().GetResult()

    if (-not $AllowFailure -and $Process.ExitCode -ne 0) {
        throw (
            "Échec : $FileName $($Arguments -join ' ')" +
            "`nCode retour : $($Process.ExitCode)" +
            "`nSTDOUT :" +
            "`n$(Protect-Text $Stdout)" +
            "`nSTDERR :" +
            "`n$(Protect-Text $Stderr)"
        )
    }

    [PSCustomObject]@{
        ExitCode = $Process.ExitCode
        Stdout   = $Stdout.TrimEnd()
        Stderr   = $Stderr.TrimEnd()
    }
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    return Invoke-Native `
        -FileName "git" `
        -Arguments (@("-C", $RepositoryPath) + $Arguments) `
        -WorkingDirectory $RepositoryPath
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure,

        [int]$TimeoutSeconds = 1800
    )

    return Invoke-Native `
        -FileName "docker" `
        -Arguments (@(
            "compose",
            "-p",
            $ProjectName,
            "-f",
            "docker-compose.yml"
        ) + $Arguments) `
        -WorkingDirectory $BackendPath `
        -AllowFailure:$AllowFailure `
        -TimeoutSeconds $TimeoutSeconds
}

function Invoke-ComposeBuildWithDnsOverrides {
    param(
        [int]$TimeoutSeconds = 1800
    )

    $TargetHosts = @(
        "repo.maven.apache.org",
        "dl-cdn.alpinelinux.org"
    )

    $HostMappings = [System.Collections.Generic.List[object]]::new()

    foreach ($TargetHost in $TargetHosts) {
        $TargetIpv4Addresses = @(
            [System.Net.Dns]::GetHostAddresses($TargetHost) |
            Where-Object {
                $_.AddressFamily -eq
                [System.Net.Sockets.AddressFamily]::InterNetwork
            } |
            ForEach-Object {
                $_.IPAddressToString
            } |
            Sort-Object -Unique
        )

        if ($TargetIpv4Addresses.Count -eq 0) {
            throw "Aucune adresse IPv4 Windows trouvée pour $TargetHost."
        }

        foreach ($TargetIp in $TargetIpv4Addresses) {
            $HostMappings.Add(
                [PSCustomObject]@{
                    Host = $TargetHost
                    Ip   = $TargetIp
                }
            )
        }
    }

    $OverridePath = Join-Path `
        $BackendPath `
        "docker-compose.smoke-build.yml"

    $OverrideLines = @(
        "services:"
        "  app:"
        "    build:"
        "      context: ."
        "      extra_hosts:"
    )

    foreach ($Mapping in $HostMappings) {
        $OverrideLines += (
            '        - "{0}:{1}"' -f
            $Mapping.Host,
            $Mapping.Ip
        )
    }

    [System.IO.File]::WriteAllText(
        $OverridePath,
        (($OverrideLines -join "`n") + "`n"),
        [System.Text.UTF8Encoding]::new($false)
    )

    $BuildResult = Invoke-Native `
        -FileName "docker" `
        -Arguments @(
            "compose",
            "-p",
            $ProjectName,
            "-f",
            "docker-compose.yml",
            "-f",
            $OverridePath,
            "build",
            "app"
        ) `
        -WorkingDirectory $BackendPath `
        -AllowFailure `
        -TimeoutSeconds $TimeoutSeconds

    $MappingSummary = @(
        $TargetHosts |
        ForEach-Object {
            $ResolvedHostName = $_
            $Ips = @(
                $HostMappings |
                Where-Object {
                    $_.Host -eq $ResolvedHostName
                } |
                ForEach-Object { $_.Ip }
            )

            "{0} ({1})" -f
            $ResolvedHostName,
            ($Ips -join ", ")
        }
    )

    if ($BuildResult.ExitCode -ne 0) {
        throw (
            "Échec du build Compose avec résolutions DNS injectées." +
            "`nHôtes : $($MappingSummary -join ' ; ')" +
            "`nSTDOUT :" +
            "`n$(Protect-Text $BuildResult.Stdout)" +
            "`nSTDERR :" +
            "`n$(Protect-Text $BuildResult.Stderr)"
        )
    }

    Write-Host "Build DNS        : extra_hosts dynamiques"
    foreach ($Line in $MappingSummary) {
        Write-Host "                   $Line"
    }

    return $BuildResult
}

function New-RandomBase64Url {
    param([int]$ByteCount)

    $Bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-RandomCharacter {
    param([Parameter(Mandatory = $true)][string]$Characters)

    $IndexBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(4)
    $Index = [BitConverter]::ToUInt32($IndexBytes, 0) % $Characters.Length
    return $Characters[[int]$Index]
}

function New-CompliantPassword {
    param([int]$Length = 32)

    if ($Length -lt 12) {
        throw "Longueur de mot de passe insuffisante."
    }

    $Upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    $Lower = "abcdefghijkmnopqrstuvwxyz"
    $Digits = "23456789"
    $Special = "!@#%_-"
    $All = $Upper + $Lower + $Digits + $Special

    $Characters = [System.Collections.Generic.List[char]]::new()
    $Characters.Add((Get-RandomCharacter $Upper))
    $Characters.Add((Get-RandomCharacter $Lower))
    $Characters.Add((Get-RandomCharacter $Digits))
    $Characters.Add((Get-RandomCharacter $Special))

    while ($Characters.Count -lt $Length) {
        $Characters.Add((Get-RandomCharacter $All))
    }

    for ($Index = $Characters.Count - 1; $Index -gt 0; $Index--) {
        $RandomBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(4)
        $SwapIndex = [int]([BitConverter]::ToUInt32($RandomBytes, 0) % ($Index + 1))
        $Temporary = $Characters[$Index]
        $Characters[$Index] = $Characters[$SwapIndex]
        $Characters[$SwapIndex] = $Temporary
    }

    return -join $Characters
}

function Get-FreeTcpPort {
    $Listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )

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

    [System.IO.File]::WriteAllText(
        $Path,
        $Value,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Convert-ResponseContentToString {
    param([Parameter(Mandatory = $true)]$Content)

    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$Content)
    }

    return [string]$Content
}

function Get-HealthStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Endpoint,
        [int]$TimeoutSeconds = 30
    )

    $Uri = "$BaseUri/actuator/health/$Endpoint"

    try {
        $Response = Invoke-WebRequest `
            -Uri $Uri `
            -Method Get `
            -TimeoutSec $TimeoutSeconds `
            -SkipHttpErrorCheck

        $Body = Convert-ResponseContentToString $Response.Content
        $Status = $null

        try {
            $Json = $Body | ConvertFrom-Json -ErrorAction Stop
            $Status = [string]$Json.status
        }
        catch {
            $Status = $null
        }

        return [PSCustomObject]@{
            HttpStatus = [int]$Response.StatusCode
            Status     = $Status
            Body       = $Body
        }
    }
    catch {
        return [PSCustomObject]@{
            HttpStatus = 0
            Status     = $null
            Body       = $_.Exception.Message
        }
    }
}

function Wait-HealthStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Endpoint,
        [Parameter(Mandatory = $true)][string]$ExpectedStatus,
        [int]$TimeoutSeconds = 180
    )

    $Deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $Last = $null

    while ([DateTimeOffset]::UtcNow -lt $Deadline) {
        $Last = Get-HealthStatus -Endpoint $Endpoint

        if ($Last.Status -eq $ExpectedStatus) {
            if ($ExpectedStatus -eq "UP" -and $Last.HttpStatus -ne 200) {
                throw "$Endpoint annonce UP avec HTTP $($Last.HttpStatus)."
            }

            if ($ExpectedStatus -eq "DOWN" -and $Last.HttpStatus -notin @(503, 200)) {
                throw "$Endpoint annonce DOWN avec HTTP $($Last.HttpStatus)."
            }

            return $Last
        }

        Start-Sleep -Seconds 3
    }

    $LastSummary = if ($null -eq $Last) {
        "aucune réponse"
    }
    else {
        "HTTP $($Last.HttpStatus), status=$($Last.Status)"
    }

    throw (
        "État $ExpectedStatus non atteint pour $Endpoint après " +
        "$TimeoutSeconds secondes : $LastSummary"
    )
}

function Get-ComposeStartupDiagnostics {
    $Sections = [System.Collections.Generic.List[string]]::new()

    $PsResult = Invoke-Compose `
        -Arguments @(
            "ps",
            "-a"
        ) `
        -AllowFailure `
        -TimeoutSeconds 60

    $Sections.Add(
        "=== docker compose ps -a ===`n" +
        (Protect-Text (
            $PsResult.Stdout +
            "`n" +
            $PsResult.Stderr
        )).Trim()
    )

    foreach ($ServiceName in @("app", "db")) {
        $ContainerIdResult = Invoke-Compose `
            -Arguments @(
                "ps",
                "-a",
                "-q",
                $ServiceName
            ) `
            -AllowFailure `
            -TimeoutSeconds 60

        $ContainerId = $ContainerIdResult.Stdout.Trim()

        if ([string]::IsNullOrWhiteSpace($ContainerId)) {
            $Sections.Add(
                "=== état $ServiceName ===`n" +
                "conteneur introuvable"
            )
        }
        else {
            $InspectResult = Invoke-Native `
                -FileName "docker" `
                -Arguments @(
                    "inspect",
                    "--format",
                    "status={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                    $ContainerId
                ) `
                -AllowFailure `
                -TimeoutSeconds 60

            $Sections.Add(
                "=== état $ServiceName ===`n" +
                (Protect-Text (
                    $InspectResult.Stdout +
                    "`n" +
                    $InspectResult.Stderr
                )).Trim()
            )
        }

        $LogsResult = Invoke-Compose `
            -Arguments @(
                "logs",
                "--no-color",
                "--tail",
                "200",
                $ServiceName
            ) `
            -AllowFailure `
            -TimeoutSeconds 120

        $Sections.Add(
            "=== logs $ServiceName, 200 dernières lignes ===`n" +
            (Protect-Text (
                $LogsResult.Stdout +
                "`n" +
                $LogsResult.Stderr
            )).Trim()
        )
    }

    return ($Sections -join "`n`n")
}

function New-HttpClient {
    $Handler = [System.Net.Http.HttpClientHandler]::new()
    $Handler.AllowAutoRedirect = $false
    $Handler.UseCookies = $true
    $Handler.CookieContainer = [System.Net.CookieContainer]::new()

    $Client = [System.Net.Http.HttpClient]::new($Handler)
    $Client.Timeout = [TimeSpan]::FromSeconds(30)
    $Client.DefaultRequestHeaders.UserAgent.ParseAdd("Subnetory-Compose-Smoke/1.0")

    return [PSCustomObject]@{
        Handler = $Handler
        Client  = $Client
    }
}

function Invoke-HttpClientRequest {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpClient]$Client,

        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpMethod]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [hashtable]$Form,

        [AllowNull()][string]$JsonBody,

        [AllowNull()][string]$BearerToken
    )

    $Request = [System.Net.Http.HttpRequestMessage]::new($Method, $Uri)

    try {
        if ($PSBoundParameters.ContainsKey("Form")) {
            $Pairs = [System.Collections.Generic.Dictionary[string,string]]::new()
            foreach ($Key in $Form.Keys) {
                $Pairs.Add([string]$Key, [string]$Form[$Key])
            }
            $Request.Content = [System.Net.Http.FormUrlEncodedContent]::new($Pairs)
        }
        elseif ($PSBoundParameters.ContainsKey("JsonBody")) {
            $Request.Content = [System.Net.Http.StringContent]::new(
                $JsonBody,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }

        if (
            $PSBoundParameters.ContainsKey("BearerToken") -and
            -not [string]::IsNullOrWhiteSpace($BearerToken)
        ) {
            $Request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
                "Bearer",
                $BearerToken
            )
        }

        $Response = $Client.SendAsync($Request).GetAwaiter().GetResult()
        try {
            $Body = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $Location = if ($null -ne $Response.Headers.Location) {
                $Response.Headers.Location.ToString()
            }
            else {
                $null
            }

            return [PSCustomObject]@{
                StatusCode = [int]$Response.StatusCode
                Location   = $Location
                Content    = $Body
            }
        }
        finally {
            $Response.Dispose()
        }
    }
    finally {
        $Request.Dispose()
    }
}

function Get-CsrfToken {
    param([Parameter(Mandatory = $true)][string]$Html)

    $Patterns = @(
        '<input[^>]+name=["'']_csrf["''][^>]+value=["'']([^"'']+)["'']',
        '<input[^>]+value=["'']([^"'']+)["''][^>]+name=["'']_csrf["'']'
    )

    foreach ($Pattern in $Patterns) {
        $Match = [regex]::Match(
            $Html,
            $Pattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )

        if ($Match.Success) {
            return [System.Net.WebUtility]::HtmlDecode($Match.Groups[1].Value)
        }
    }

    throw "Jeton CSRF introuvable dans la page HTML."
}

function Assert-Redirect {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [Parameter(Mandatory = $true)][string]$ExpectedPath
    )

    if ($Response.StatusCode -notin @(302, 303)) {
        throw "Redirection attendue vers $ExpectedPath, HTTP obtenu : $($Response.StatusCode)."
    }

    if ([string]::IsNullOrWhiteSpace($Response.Location)) {
        throw "En-tête Location absent pour la redirection vers $ExpectedPath."
    }

    $LocationPath = if ([Uri]::IsWellFormedUriString($Response.Location, [UriKind]::Absolute)) {
        ([Uri]$Response.Location).AbsolutePath
    }
    else {
        ([Uri]::new([Uri]$BaseUri, $Response.Location)).AbsolutePath
    }

    if ($LocationPath -ne $ExpectedPath) {
        throw "Redirection inattendue : $LocationPath, attendu : $ExpectedPath."
    }
}

function Convert-Base64UrlToBytes {
    param([Parameter(Mandatory = $true)][string]$Value)

    $Padded = $Value.Replace("-", "+").Replace("_", "/")
    switch ($Padded.Length % 4) {
        2 { $Padded += "==" }
        3 { $Padded += "=" }
        0 { }
        default { throw "Segment Base64URL JWT invalide." }
    }

    return [Convert]::FromBase64String($Padded)
}

function Test-FixedTimeEquals {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Left,
        [Parameter(Mandatory = $true)][byte[]]$Right
    )

    if ($Left.Length -ne $Right.Length) {
        return $false
    }

    $Difference = 0
    for ($Index = 0; $Index -lt $Left.Length; $Index++) {
        $Difference = $Difference -bor ($Left[$Index] -bxor $Right[$Index])
    }

    return $Difference -eq 0
}

function Assert-JwtSignature {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [Parameter(Mandatory = $true)][string]$Secret
    )

    $Segments = $Token.Split(".")
    if ($Segments.Count -ne 3) {
        throw "JWT mal formé."
    }

    $SigningInput = [System.Text.Encoding]::ASCII.GetBytes(
        $Segments[0] + "." + $Segments[1]
    )

    $Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $Hmac = [System.Security.Cryptography.HMACSHA256]::new($Key)
    try {
        $ExpectedSignature = $Hmac.ComputeHash($SigningInput)
    }
    finally {
        $Hmac.Dispose()
    }

    $ActualSignature = Convert-Base64UrlToBytes $Segments[2]

    if (-not (Test-FixedTimeEquals $ExpectedSignature $ActualSignature)) {
        throw "La signature JWT ne correspond pas au secret config tree généré."
    }

    $PayloadJson = [System.Text.Encoding]::UTF8.GetString(
        (Convert-Base64UrlToBytes $Segments[1])
    )
    $Payload = $PayloadJson | ConvertFrom-Json

    if ($Payload.iss -ne "subnetory" -or $Payload.sub -ne "admin") {
        throw "Claims JWT inattendus."
    }
}

function Assert-ImageExportContainsNoSecrets {
    param(
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)][string]$ExportPath,
        [Parameter(Mandatory = $true)][string]$PatternPath
    )

    $ScanResult = Invoke-Native `
        -FileName "docker" `
        -Arguments @(
            "run",
            "--rm",
            "--entrypoint",
            "sh",
            "--mount",
            "type=bind,source=$ExportPath,target=/scan/image.tar,readonly",
            "--mount",
            "type=bind,source=$PatternPath,target=/scan/patterns,readonly",
            $ImageId,
            "-ec",
            "if grep -aFq -f /scan/patterns /scan/image.tar; then exit 42; fi"
        ) `
        -AllowFailure `
        -TimeoutSeconds 600

    if ($ScanResult.ExitCode -eq 42) {
        throw "Une valeur secrète générée est embarquée dans l'image."
    }

    if ($ScanResult.ExitCode -ne 0) {
        throw "Inspection binaire de l'image impossible, code $($ScanResult.ExitCode)."
    }
}

function Assert-NoSecretValuesInText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )

    foreach ($Entry in $SecretEntries) {
        if ($Text.Contains($Entry.Value)) {
            throw "Valeur secrète détectée dans $Source : $($Entry.Name)."
        }
    }
}

function Get-ContainerSecretHash {
    param(
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $Result = Invoke-Compose -Arguments @(
        "exec",
        "-T",
        $Service,
        "sha256sum",
        $Path
    )

    $Fields = $Result.Stdout.Trim() -split "\s+"
    if ($Fields.Count -lt 1 -or $Fields[0] -notmatch "^[0-9a-fA-F]{64}$") {
        throw "SHA256 conteneur invalide pour ${Service}:$Path."
    }

    return $Fields[0].ToUpperInvariant()
}

function Wait-PostgresHealthy {
    param([int]$TimeoutSeconds = 120)

    $Deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $Deadline) {
        $Result = Invoke-Compose `
            -Arguments @(
                "exec",
                "-T",
                "db",
                "pg_isready",
                "-U",
                "subnetory",
                "-d",
                "subnetory"
            ) `
            -AllowFailure `
            -TimeoutSeconds 20

        if ($Result.ExitCode -eq 0) {
            return
        }

        Start-Sleep -Seconds 3
    }

    throw "PostgreSQL n'est pas redevenu disponible."
}

function Assert-NoComposeResiduals {
    $ContainerIds = (
        Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "ps",
                "-a",
                "--filter",
                "label=com.docker.compose.project=$ProjectName",
                "--format",
                "{{.ID}}"
            ) `
            -AllowFailure
    ).Stdout.Trim()

    $NetworkIds = (
        Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "network",
                "ls",
                "--filter",
                "label=com.docker.compose.project=$ProjectName",
                "--format",
                "{{.ID}}"
            ) `
            -AllowFailure
    ).Stdout.Trim()

    $VolumeNames = (
        Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "volume",
                "ls",
                "--filter",
                "label=com.docker.compose.project=$ProjectName",
                "--format",
                "{{.Name}}"
            ) `
            -AllowFailure
    ).Stdout.Trim()

    if (
        -not [string]::IsNullOrWhiteSpace($ContainerIds) -or
        -not [string]::IsNullOrWhiteSpace($NetworkIds) -or
        -not [string]::IsNullOrWhiteSpace($VolumeNames)
    ) {
        throw "Ressources Compose résiduelles détectées pour $ProjectName."
    }
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $RepositoryPath = (
        Invoke-Native `
            -FileName "git" `
            -Arguments @(
                "-C",
                (Join-Path $PSScriptRoot ".."),
                "rev-parse",
                "--show-toplevel"
            )
    ).Stdout.Trim()
}

$RepositoryPath = [System.IO.Path]::GetFullPath($RepositoryPath)
$TempRoot = Join-Path (
    [System.IO.Path]::GetTempPath()
) ("subnetory-compose-smoke-" + [guid]::NewGuid().ToString("N"))
$ArchivePath = Join-Path $TempRoot "backend-source.zip"
$SourceRoot = Join-Path $TempRoot "source"
$BackendPath = Join-Path $SourceRoot "backend"
$ImageExportPath = Join-Path $TempRoot "app-image.tar"
$ImagePatternPath = Join-Path $TempRoot "secret-patterns.txt"
$ProjectName = (
    "subnetory-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
).ToLowerInvariant()
$InspectContainerName = "$ProjectName-image-inspect"
$HostPort = Get-FreeTcpPort
$BaseUri = "http://127.0.0.1:$HostPort"

$JwtSecret = New-RandomBase64Url -ByteCount 64
$PostgresPassword = New-RandomBase64Url -ByteCount 36
$TemporaryAdminPassword = New-CompliantPassword -Length 32
$NewAdminPassword = New-CompliantPassword -Length 36
$BackupEncryptionKey = New-RandomBase64Url -ByteCount 36

$SecretEntries = @(
    [PSCustomObject]@{ Name = "JWT"; Value = $JwtSecret },
    [PSCustomObject]@{ Name = "PostgreSQL"; Value = $PostgresPassword },
    [PSCustomObject]@{ Name = "Admin temporaire"; Value = $TemporaryAdminPassword },
    [PSCustomObject]@{ Name = "Admin final"; Value = $NewAdminPassword },
    [PSCustomObject]@{ Name = "Chiffrement sauvegardes"; Value = $BackupEncryptionKey }
)
$script:SecretValues = @($SecretEntries | ForEach-Object { $_.Value })

$EnvironmentNames = @(
    "HOST_PORT",
    "SERVER_PORT",
    "POSTGRES_USER",
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SUBNETORY_JWT_SECRET",
    "SUBNETORY_ADMIN_DEFAULT_PASSWORD",
    "COMPOSE_FILE",
    "COMPOSE_PROJECT_NAME",
    "COMPOSE_PROFILES"
)
$SavedEnvironment = @{}
foreach ($Name in $EnvironmentNames) {
    $SavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
}

$RawLogs = ""
$SmokeSucceeded = $false

Write-Host "=== Smoke test Docker Compose autonome ===" -ForegroundColor Cyan
Write-Host "PowerShell       : $($PSVersionTable.PSVersion)"
Write-Host "Projet Compose   : $ProjectName"
Write-Host "Port HTTP        : $HostPort"
Write-Host "Secrets          : éphémères, valeurs masquées"

try {
    try {
        $null = Get-Command git -ErrorAction Stop
        $null = Get-Command docker -ErrorAction Stop

        Assert-ComposePortParserSelfTest

        if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) {
            throw "Dépôt introuvable : $RepositoryPath"
        }

        $Status = Invoke-Git -Arguments @(
            "status",
            "--porcelain=v1",
            "--untracked-files=all"
        )

        if (-not [string]::IsNullOrWhiteSpace($Status.Stdout)) {
            throw "Le working tree doit être propre avant le smoke test."
        }

        $HeadCommit = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Stdout.Trim()
        Write-Host "Commit testé     : $HeadCommit"

        foreach ($Name in @(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SUBNETORY_JWT_SECRET",
            "SUBNETORY_ADMIN_DEFAULT_PASSWORD",
            "COMPOSE_FILE",
            "COMPOSE_PROJECT_NAME",
            "COMPOSE_PROFILES"
        )) {
            [Environment]::SetEnvironmentVariable($Name, $null, "Process")
        }

        [Environment]::SetEnvironmentVariable("HOST_PORT", [string]$HostPort, "Process")
        [Environment]::SetEnvironmentVariable("SERVER_PORT", "8080", "Process")
        [Environment]::SetEnvironmentVariable("POSTGRES_USER", "subnetory", "Process")

        New-Item -ItemType Directory -Path $SourceRoot -Force | Out-Null

        $null = Invoke-Native `
            -FileName "git" `
            -Arguments @(
                "-C",
                $RepositoryPath,
                "-c",
                "core.autocrlf=false",
                "archive",
                "--format=zip",
                "--output=$ArchivePath",
                "HEAD",
                "backend"
            ) `
            -WorkingDirectory $RepositoryPath

        Expand-Archive `
            -LiteralPath $ArchivePath `
            -DestinationPath $SourceRoot `
            -Force

        if (-not (Test-Path -LiteralPath $BackendPath -PathType Container)) {
            throw "Le répertoire backend est absent de l'archive Git."
        }

        $SecretsPath = Join-Path $BackendPath "secrets"
        New-Item -ItemType Directory -Path $SecretsPath -Force | Out-Null

        $SecretPaths = @{
            Jwt = Join-Path $SecretsPath "subnetory_jwt_secret"
            Admin = Join-Path $SecretsPath "subnetory_admin_default_password"
            Postgres = Join-Path $SecretsPath "postgres_password"
            BackupEncryption = Join-Path $SecretsPath "subnetory_backup_encryption_key"
        }

        Write-Utf8NoBomNoNewline -Path $SecretPaths.Jwt -Value $JwtSecret
        Write-Utf8NoBomNoNewline -Path $SecretPaths.Admin -Value $TemporaryAdminPassword
        Write-Utf8NoBomNoNewline -Path $SecretPaths.Postgres -Value $PostgresPassword
        Write-Utf8NoBomNoNewline -Path $SecretPaths.BackupEncryption -Value $BackupEncryptionKey

        Write-Host "`n=== Configuration Compose ===" -ForegroundColor Cyan

        $AutonomousConfig = Invoke-Compose -Arguments @("config")
        Assert-NoSecretValuesInText `
            -Text ($AutonomousConfig.Stdout + "`n" + $AutonomousConfig.Stderr) `
            -Source "docker compose config autonome"

        $ProdConfig = Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "compose",
                "-p",
                "$ProjectName-prod",
                "-f",
                "docker-compose.prod.yml",
                "config"
            ) `
            -WorkingDirectory $BackendPath

        Assert-NoSecretValuesInText `
            -Text ($ProdConfig.Stdout + "`n" + $ProdConfig.Stderr) `
            -Source "docker compose config production"

        Write-Host "Compose autonome : configuration valide"
        Write-Host "Compose prod     : configuration valide"

        Write-Host "`n=== Build et inspection de l'image ===" -ForegroundColor Cyan

        $null = Invoke-ComposeBuildWithDnsOverrides -TimeoutSeconds 1800

        $ComposeModelResult = Invoke-Compose `
            -Arguments @(
                "config",
                "--format",
                "json"
            )

        try {
            $ComposeModel = $ComposeModelResult.Stdout |
                ConvertFrom-Json -Depth 100
        }
        catch {
            throw (
                "Impossible de parser le modèle Compose JSON." +
                "`nErreur : $($_.Exception.Message)" +
                "`nSortie :" +
                "`n$(Protect-Text $ComposeModelResult.Stdout)"
            )
        }

        if ($null -eq $ComposeModel.services) {
            throw "Section services absente du modèle Compose JSON."
        }

        $AppServiceProperty = $ComposeModel.services.PSObject.Properties["app"]

        if ($null -eq $AppServiceProperty) {
            throw "Service app absent du modèle Compose JSON."
        }

        $AppImageProperty = `
            $AppServiceProperty.Value.PSObject.Properties["image"]

        $DefaultAppImageReference = "{0}-app" -f $ProjectName

        if (
            $null -ne $AppImageProperty -and
            -not [string]::IsNullOrWhiteSpace(
                [string]$AppImageProperty.Value
            )
        ) {
            $AppImageReference = [string]$AppImageProperty.Value
            $AppImageReferenceSource = "image explicite"
        }
        else {
            $AppImageReference = $DefaultAppImageReference
            $AppImageReferenceSource = "nom Compose par défaut"
        }

        $script:AppImageId = (
            Invoke-Native `
                -FileName "docker" `
                -Arguments @(
                    "image",
                    "inspect",
                    "--format",
                    "{{.Id}}",
                    $AppImageReference
                )
        ).Stdout.Trim()

        if ([string]::IsNullOrWhiteSpace($script:AppImageId)) {
            throw (
                "Identifiant de l'image applicative introuvable." +
                "`nRéférence : $AppImageReference"
            )
        }

        Write-Host "Image référence  : $AppImageReference"
        Write-Host "Image source     : $AppImageReferenceSource"
        Write-Host "Image ID         : $($script:AppImageId)"

        $ImageUser = (
            Invoke-Native `
                -FileName "docker" `
                -Arguments @(
                    "image",
                    "inspect",
                    "--format",
                    "{{.Config.User}}",
                    $script:AppImageId
                )
        ).Stdout.Trim()

        if ($ImageUser -ne "subnetory") {
            throw "Utilisateur configuré dans l'image incorrect : $ImageUser"
        }

        $CreateResult = Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "create",
                "--name",
                $InspectContainerName,
                "--entrypoint",
                "sh",
                $script:AppImageId,
                "-c",
                "true"
            )

        $script:InspectContainer = $InspectContainerName

        $null = Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "export",
                "--output",
                $ImageExportPath,
                $InspectContainerName
            ) `
            -TimeoutSeconds 600

        [System.IO.File]::WriteAllLines(
            $ImagePatternPath,
            [string[]]@($SecretEntries | ForEach-Object { $_.Value }),
            [System.Text.UTF8Encoding]::new($false)
        )

        Assert-ImageExportContainsNoSecrets `
            -ImageId $script:AppImageId `
            -ExportPath $ImageExportPath `
            -PatternPath $ImagePatternPath

        $NoSecretFiles = Invoke-Native `
            -FileName "docker" `
            -Arguments @(
                "run",
                "--rm",
                "--entrypoint",
                "sh",
                $script:AppImageId,
                "-ec",
                "test ! -e /run/secrets/subnetory.jwt.secret && test ! -e /run/secrets/subnetory.admin.default-password && test ! -e /run/secrets/spring.datasource.password && test ! -e /run/secrets/subnetory.backup.encryption.key"
            )

        Write-Host "Image            : construite"
        Write-Host "Utilisateur image : subnetory"
        Write-Host "Secrets image    : aucune valeur ni fichier embarqué"

        Write-Host "`n=== Démarrage autonome ===" -ForegroundColor Cyan

        $null = Invoke-Compose `
            -Arguments @("up", "-d", "--no-build") `
            -TimeoutSeconds 600
        $script:ComposeStarted = $true

        try {
            $null = Wait-HealthStatus `
                -Endpoint "readiness" `
                -ExpectedStatus "UP" `
                -TimeoutSeconds 240

            $LivenessInitial = Wait-HealthStatus `
                -Endpoint "liveness" `
                -ExpectedStatus "UP" `
                -TimeoutSeconds 60
        }
        catch {
            $StartupFailure = $_.Exception.Message
            $StartupDiagnostics = Get-ComposeStartupDiagnostics

            throw (
                "Démarrage autonome non validé." +
                "`nErreur readiness/liveness :" +
                "`n$StartupFailure" +
                "`n`nDiagnostics Docker expurgés :" +
                "`n$StartupDiagnostics"
            )
        }

        $AppContainerId = (Invoke-Compose -Arguments @("ps", "-q", "app")).Stdout.Trim()
        $DbContainerId = (Invoke-Compose -Arguments @("ps", "-q", "db")).Stdout.Trim()

        if ([string]::IsNullOrWhiteSpace($AppContainerId) -or [string]::IsNullOrWhiteSpace($DbContainerId)) {
            throw "Conteneurs applicatif ou PostgreSQL introuvables."
        }

        $AppInspect = @((
            Invoke-Native `
                -FileName "docker" `
                -Arguments @("inspect", $AppContainerId)
        ).Stdout | ConvertFrom-Json)[0]

        $DbInspect = @((
            Invoke-Native `
                -FileName "docker" `
                -Arguments @("inspect", $DbContainerId)
        ).Stdout | ConvertFrom-Json)[0]

        foreach ($Entry in @($AppInspect.Config.Env)) {
            if ($Entry -match "^(SPRING_DATASOURCE_PASSWORD|SUBNETORY_JWT_SECRET|SUBNETORY_ADMIN_DEFAULT_PASSWORD)=") {
                throw "Secret applicatif injecté directement dans l'environnement."
            }
        }

        foreach ($Entry in @($DbInspect.Config.Env)) {
            if ($Entry -match "^POSTGRES_PASSWORD=") {
                throw "Mot de passe PostgreSQL injecté directement dans l'environnement."
            }
        }

        $DbHostBindings = @()
        $DbPortBindings = $DbInspect.HostConfig.PortBindings

        if ($null -ne $DbPortBindings) {
            $DbPortBindingProperty = `
                $DbPortBindings.PSObject.Properties["5432/tcp"]

            if (
                $null -ne $DbPortBindingProperty -and
                $null -ne $DbPortBindingProperty.Value
            ) {
                $DbHostBindings = @(
                    $DbPortBindingProperty.Value |
                    Where-Object {
                        -not [string]::IsNullOrWhiteSpace(
                            [string]$_.HostPort
                        )
                    }
                )
            }
        }

        $DbComposePortResult = Invoke-Compose `
            -Arguments @(
                "port",
                "db",
                "5432"
            ) `
            -AllowFailure `
            -TimeoutSeconds 60

        $DbPublishedPort = $DbComposePortResult.Stdout.Trim()

        # Certaines versions de Compose retournent exit 0 avec une
        # sentinelle telle que "invalid IP:0" lorsqu'aucun port n'est
        # publié. Le même parseur que celui couvert par l'auto-test V16
        # n'accepte qu'un port hôte compris entre 1 et 65535.
        $DbComposePortConfirmed = (
            $DbComposePortResult.ExitCode -eq 0 -and
            (
                Test-ComposePortOutputHasPublishedBinding `
                    -Text $DbPublishedPort
            )
        )

        if (
            $DbHostBindings.Count -gt 0 -or
            $DbComposePortConfirmed
        ) {
            throw (
                "PostgreSQL est exposé vers l'hôte." +
                "`nLiaison Compose : $DbPublishedPort"
            )
        }

        if ($DbComposePortResult.ExitCode -ne 0) {
            Write-Host (
                "Vérification compose port : aucune liaison confirmée " +
                "(code $($DbComposePortResult.ExitCode))"
            )
        }

        Write-Host "PostgreSQL hôte : aucun port publié"

        $RuntimeUid = (
            Invoke-Compose -Arguments @("exec", "-T", "app", "id", "-u")
        ).Stdout.Trim()
        $RuntimeUser = (
            Invoke-Compose -Arguments @("exec", "-T", "app", "id", "-un")
        ).Stdout.Trim()

        if ([int]$RuntimeUid -eq 0 -or $RuntimeUser -ne "subnetory") {
            throw "Le processus applicatif ne s'exécute pas en non-root subnetory."
        }

        $HostJwtHash = (Get-FileHash -LiteralPath $SecretPaths.Jwt -Algorithm SHA256).Hash.ToUpperInvariant()
        $HostAdminHash = (Get-FileHash -LiteralPath $SecretPaths.Admin -Algorithm SHA256).Hash.ToUpperInvariant()
        $HostDbHash = (Get-FileHash -LiteralPath $SecretPaths.Postgres -Algorithm SHA256).Hash.ToUpperInvariant()
        $HostBackupEncryptionHash = (Get-FileHash -LiteralPath $SecretPaths.BackupEncryption -Algorithm SHA256).Hash.ToUpperInvariant()

        if ((Get-ContainerSecretHash -Service "app" -Path "/run/secrets/subnetory.jwt.secret") -ne $HostJwtHash) {
            throw "Secret JWT monté différent du fichier temporaire."
        }
        if ((Get-ContainerSecretHash -Service "app" -Path "/run/secrets/subnetory.admin.default-password") -ne $HostAdminHash) {
            throw "Secret bootstrap admin monté différent du fichier temporaire."
        }
        if ((Get-ContainerSecretHash -Service "app" -Path "/run/secrets/spring.datasource.password") -ne $HostDbHash) {
            throw "Secret datasource monté différent du fichier temporaire."
        }
        if ((Get-ContainerSecretHash -Service "db" -Path "/run/secrets/postgres_password") -ne $HostDbHash) {
            throw "Secret PostgreSQL monté différent du fichier temporaire."
        }
        if ((Get-ContainerSecretHash -Service "app" -Path "/run/secrets/subnetory.backup.encryption.key") -ne $HostBackupEncryptionHash) {
            throw "Secret de chiffrement des sauvegardes monté différent du fichier temporaire."
        }

        $DbPasswordLogin = Invoke-Compose -Arguments @(
            "exec",
            "-T",
            "db",
            "sh",
            "-ec",
            'export PGPASSWORD="$(cat /run/secrets/postgres_password)"; psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1"'
        )

        if ($DbPasswordLogin.Stdout.Trim() -ne "1") {
            throw "Connexion PostgreSQL avec le secret fichier échouée."
        }

        $FlywayResult = Invoke-Compose -Arguments @(
            "exec",
            "-T",
            "db",
            "psql",
            "-U",
            "subnetory",
            "-d",
            "subnetory",
            "-tAc",
            "SELECT count(*) || ':' || (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1) FROM flyway_schema_history WHERE success;"
        )

        $ExpectedFlyway = Get-ExpectedFlywayState
        $ExpectedFlywayText = "$($ExpectedFlyway.Count):$($ExpectedFlyway.LatestVersion)"
        if ($FlywayResult.Stdout.Trim() -ne $ExpectedFlywayText) {
            throw "État Flyway inattendu : $($FlywayResult.Stdout.Trim())"
        }

        Write-Host "Readiness        : UP"
        Write-Host "Liveness         : UP"
        Write-Host "Processus        : subnetory non-root"
        Write-Host "Config tree      : quatre secrets applicatifs liés"
        Write-Host "PostgreSQL       : login secret réussi, aucun port hôte"
        Write-Host "Flyway           : $($ExpectedFlyway.Count) migrations, version V$($ExpectedFlyway.LatestVersion)"

        Write-Host "`n=== Parcours administrateur et JWT ===" -ForegroundColor Cyan

        $Http = New-HttpClient
        try {
            $LoginPage = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/login"

            if ($LoginPage.StatusCode -ne 200) {
                throw "Page de login inaccessible : HTTP $($LoginPage.StatusCode)."
            }

            $LoginCsrf = Get-CsrfToken -Html $LoginPage.Content
            $LoginResponse = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Post) `
                -Uri "$BaseUri/login" `
                -Form @{
                    username = "admin"
                    password = $TemporaryAdminPassword
                    _csrf = $LoginCsrf
                }

            Assert-Redirect `
                -Response $LoginResponse `
                -ExpectedPath "/profile/change-password-required"

            $BlockedDashboard = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/"

            Assert-Redirect `
                -Response $BlockedDashboard `
                -ExpectedPath "/profile/change-password-required"

            $TemporaryTokenResponse = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Post) `
                -Uri "$BaseUri/api/v1/auth/token" `
                -JsonBody (@{
                    username = "admin"
                    password = $TemporaryAdminPassword
                } | ConvertTo-Json -Compress)

            if (
                $TemporaryTokenResponse.StatusCode -ne 403 -or
                $TemporaryTokenResponse.Content -notmatch 'PASSWORD_CHANGE_REQUIRED'
            ) {
                throw "Le JWT n'est pas bloqué avant le changement obligatoire."
            }

            $ChangePage = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/profile/change-password-required"

            if ($ChangePage.StatusCode -ne 200) {
                throw "Page de changement obligatoire inaccessible."
            }

            $ChangeCsrf = Get-CsrfToken -Html $ChangePage.Content
            $ChangeResponse = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Post) `
                -Uri "$BaseUri/profile/change-password-required" `
                -Form @{
                    currentPassword = $TemporaryAdminPassword
                    newPassword = $NewAdminPassword
                    confirmPassword = $NewAdminPassword
                    _csrf = $ChangeCsrf
                }

            Assert-Redirect -Response $ChangeResponse -ExpectedPath "/"

            # Le changement de mot de passe invalide les anciens JWT avec
            # un seuil temporel. Cette pause évite qu'un nouveau jeton émis
            # dans la même seconde soit considéré antérieur à ce seuil.
            Write-Host "Synchronisation JWT : pause 1,1 seconde"
            Start-Sleep -Milliseconds 1100

            $Dashboard = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/"

            if (
                $Dashboard.StatusCode -ne 200 -or
                $Dashboard.Content -notmatch "Tableau de bord"
            ) {
                throw "Dashboard inaccessible après changement du mot de passe."
            }

            $TokenResponse = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Post) `
                -Uri "$BaseUri/api/v1/auth/token" `
                -JsonBody (@{
                    username = "admin"
                    password = $NewAdminPassword
                } | ConvertTo-Json -Compress)

            if ($TokenResponse.StatusCode -ne 200) {
                throw "Émission JWT échouée : HTTP $($TokenResponse.StatusCode)."
            }

            $TokenJson = $TokenResponse.Content | ConvertFrom-Json
            $AccessToken = [string]$TokenJson.accessToken

            if ([string]::IsNullOrWhiteSpace($AccessToken)) {
                throw "JWT absent de la réponse."
            }

            Assert-JwtSignature -Token $AccessToken -Secret $JwtSecret

            $ProtectedApi = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/api/v1/contexts" `
                -BearerToken $AccessToken

            if (
                $ProtectedApi.StatusCode -ne 200 -or
                $ProtectedApi.Content -notmatch '"content"'
            ) {
                throw "JWT refusé par l'API protégée."
            }

            Write-Host "Login temporaire  : réussi"
            Write-Host "Blocage initial   : Web et JWT confirmés"
            Write-Host "Changement mot de passe : réussi"
            Write-Host "Dashboard         : accessible"
            Write-Host "JWT               : signature config tree et API validées"

            Write-Host "`n=== Redémarrage applicatif et persistance ===" -ForegroundColor Cyan

            $null = Invoke-Compose -Arguments @("restart", "app") -TimeoutSeconds 180
            $null = Wait-HealthStatus -Endpoint "readiness" -ExpectedStatus "UP" -TimeoutSeconds 180

            $ApiAfterRestart = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/api/v1/contexts" `
                -BearerToken $AccessToken

            if ($ApiAfterRestart.StatusCode -ne 200) {
                throw "JWT non persistant après redémarrage applicatif."
            }

            $PersistedState = Invoke-Compose -Arguments @(
                "exec",
                "-T",
                "db",
                "psql",
                "-U",
                "subnetory",
                "-d",
                "subnetory",
                "-tAc",
                "SELECT CASE WHEN password IS NOT NULL AND must_change_password = false THEN 'persisted' ELSE 'invalid' END FROM users WHERE username = 'admin';"
            )

            if ($PersistedState.Stdout.Trim() -ne "persisted") {
                throw "Mot de passe administrateur ou état must_change_password non persistant."
            }

            $ReloginHttp = New-HttpClient
            try {
                $ReloginPage = Invoke-HttpClientRequest `
                    -Client $ReloginHttp.Client `
                    -Method ([System.Net.Http.HttpMethod]::Get) `
                    -Uri "$BaseUri/login"
                $ReloginCsrf = Get-CsrfToken -Html $ReloginPage.Content
                $Relogin = Invoke-HttpClientRequest `
                    -Client $ReloginHttp.Client `
                    -Method ([System.Net.Http.HttpMethod]::Post) `
                    -Uri "$BaseUri/login" `
                    -Form @{
                        username = "admin"
                        password = $NewAdminPassword
                        _csrf = $ReloginCsrf
                    }

                Assert-Redirect -Response $Relogin -ExpectedPath "/"
            }
            finally {
                $ReloginHttp.Client.Dispose()
                $ReloginHttp.Handler.Dispose()
            }

            Write-Host "Redémarrage app   : readiness revenue UP"
            Write-Host "Persistance      : mot de passe, état et JWT confirmés"

            Write-Host "`n=== Panne et reprise PostgreSQL ===" -ForegroundColor Cyan

            $null = Invoke-Compose -Arguments @("stop", "db") -TimeoutSeconds 120
            $null = Wait-HealthStatus -Endpoint "readiness" -ExpectedStatus "DOWN" -TimeoutSeconds 150
            $null = Wait-HealthStatus -Endpoint "liveness" -ExpectedStatus "UP" -TimeoutSeconds 60

            $null = Invoke-Compose -Arguments @("start", "db") -TimeoutSeconds 120
            Wait-PostgresHealthy -TimeoutSeconds 120
            $null = Wait-HealthStatus -Endpoint "readiness" -ExpectedStatus "UP" -TimeoutSeconds 180

            $ApiAfterDbRecovery = Invoke-HttpClientRequest `
                -Client $Http.Client `
                -Method ([System.Net.Http.HttpMethod]::Get) `
                -Uri "$BaseUri/api/v1/contexts" `
                -BearerToken $AccessToken

            if ($ApiAfterDbRecovery.StatusCode -ne 200) {
                throw "API non rétablie après le redémarrage PostgreSQL."
            }

            Write-Host "DB arrêtée       : readiness DOWN, liveness UP"
            Write-Host "DB redémarrée    : readiness et API revenues UP"
        }
        finally {
            $Http.Client.Dispose()
            $Http.Handler.Dispose()
        }

        $SmokeSucceeded = $true
    }
    catch {
        $script:MainFailure = $_
    }
}
finally {
    if ($script:ComposeStarted -and (Test-Path -LiteralPath $BackendPath)) {
        try {
            $LogResult = Invoke-Compose `
                -Arguments @("logs", "--no-color", "--timestamps") `
                -AllowFailure `
                -TimeoutSeconds 180
            $RawLogs = $LogResult.Stdout + "`n" + $LogResult.Stderr

            foreach ($Entry in $SecretEntries) {
                if ($RawLogs.Contains($Entry.Value)) {
                    $script:LogLeaks.Add($Entry.Name)
                }
            }

            if (-not [string]::IsNullOrWhiteSpace($RedactedLogPath)) {
                $ResolvedLogPath = [System.IO.Path]::GetFullPath($RedactedLogPath)
                $LogDirectory = Split-Path -Parent $ResolvedLogPath
                if (-not [string]::IsNullOrWhiteSpace($LogDirectory)) {
                    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
                }

                [System.IO.File]::WriteAllText(
                    $ResolvedLogPath,
                    (Protect-Text $RawLogs),
                    [System.Text.UTF8Encoding]::new($false)
                )
            }
        }
        catch {
            $script:CleanupErrors.Add(
                "Collecte ou expurgation des logs : $($_.Exception.Message)"
            )
        }
    }

    if (Test-Path -LiteralPath $BackendPath) {
        try {
            $DownResult = Invoke-Compose `
                -Arguments @(
                    "down",
                    "-v",
                    "--remove-orphans",
                    "--rmi",
                    "local"
                ) `
                -AllowFailure `
                -TimeoutSeconds 600

            if ($DownResult.ExitCode -ne 0) {
                $script:CleanupErrors.Add(
                    "docker compose down -v a retourné $($DownResult.ExitCode)."
                )
            }
        }
        catch {
            $script:CleanupErrors.Add(
                "docker compose down -v : $($_.Exception.Message)"
            )
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($script:InspectContainer)) {
        try {
            $null = Invoke-Native `
                -FileName "docker" `
                -Arguments @("rm", "-f", $script:InspectContainer) `
                -AllowFailure `
                -TimeoutSeconds 120
        }
        catch {
            $script:CleanupErrors.Add(
                "Suppression du conteneur d'inspection : $($_.Exception.Message)"
            )
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($script:AppImageId)) {
        try {
            $null = Invoke-Native `
                -FileName "docker" `
                -Arguments @("image", "rm", "-f", $script:AppImageId) `
                -AllowFailure `
                -TimeoutSeconds 300

            $ImageStillExists = Invoke-Native `
                -FileName "docker" `
                -Arguments @("image", "inspect", $script:AppImageId) `
                -AllowFailure `
                -TimeoutSeconds 120

            if ($ImageStillExists.ExitCode -eq 0) {
                $script:CleanupErrors.Add("L'image applicative de test subsiste.")
            }
        }
        catch {
            $script:CleanupErrors.Add(
                "Suppression de l'image applicative : $($_.Exception.Message)"
            )
        }
    }

    try {
        Assert-NoComposeResiduals
    }
    catch {
        $script:CleanupErrors.Add($_.Exception.Message)
    }

    if (Test-Path -LiteralPath $TempRoot) {
        try {
            Remove-Item -LiteralPath $TempRoot -Recurse -Force
        }
        catch {
            $script:CleanupErrors.Add(
                "Suppression du répertoire temporaire : $($_.Exception.Message)"
            )
        }
    }

    foreach ($Name in $EnvironmentNames) {
        [Environment]::SetEnvironmentVariable(
            $Name,
            $SavedEnvironment[$Name],
            "Process"
        )
    }
}

if ($script:LogLeaks.Count -gt 0) {
    throw (
        "Valeurs secrètes détectées dans les logs Docker : " +
        (($script:LogLeaks | Sort-Object -Unique) -join ", ")
    )
}

if ($script:CleanupErrors.Count -gt 0) {
    throw (
        "Nettoyage incomplet :" +
        "`n- " +
        ($script:CleanupErrors -join "`n- ")
    )
}

if ($null -ne $script:MainFailure) {
    throw $script:MainFailure
}

if (-not $SmokeSucceeded) {
    throw "Le smoke test n'a pas atteint son état final."
}

Write-Host "`n=== Résultat final ===" -ForegroundColor Green
Write-Host "Compose config   : autonome et production valides"
Write-Host "Build DNS        : Maven Central et Alpine CDN, sans réseau host"
Write-Host "Diagnostic       : ps, états et logs expurgés si démarrage impossible"
Write-Host "Image            : aucun secret embarqué"
Write-Host "Processus        : non-root"
Write-Host "Config tree      : DB, JWT et bootstrap liés effectivement"
Write-Host "Admin            : changement obligatoire et persistance validés"
Write-Host "JWT              : signature et accès API validés"
$ExpectedFlyway = Get-ExpectedFlywayState
Write-Host "Flyway           : V1 à V$($ExpectedFlyway.LatestVersion)"
Write-Host "Panne DB         : readiness DOWN / liveness UP"
Write-Host "Reprise DB       : automatique"
Write-Host "Logs             : aucune valeur secrète détectée"
Write-Host "Nettoyage        : conteneurs, réseau, volume, image et secrets supprimés"
# Marqueur ASCII stable pour les lanceurs qui capturent stdout.
Write-Host "`nSMOKE TEST COMPOSE AUTONOME VALIDE" -ForegroundColor Green
