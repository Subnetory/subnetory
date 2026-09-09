<#
.SYNOPSIS
Audite lʼAPI REST Subnetory à partir de son contrat OpenAPI.

.DESCRIPTION
Inventorie les opérations /api/v1, exécute les scénarios fonctionnels et de
sécurité, crée des données réseau dans chaque contexte, contrôle le journal
dʼaudit et produit les preuves JSON/CSV/Markdown dans OutputDirectory.

Utiliser une base isolée : avec AllowDestructiveTests, le script restaure une
sauvegarde, supprime des ressources jetables et purge le journal dʼaudit.

.EXAMPLE
$password = Read-Host 'Mot de passe administrateur' -AsSecureString
./scripts/audit-api.ps1 -BaseUrl 'http://127.0.0.1:8080' `
  -AdminPassword $password -OutputDirectory './reports/api-audit'

.EXAMPLE
# Réservé à une instance jetable, jamais à la production.
./scripts/audit-api.ps1 -BaseUrl 'http://127.0.0.1:18082' `
  -AdminPasswordFile 'C:\temp\audit-admin-password.txt' `
  -OutputDirectory 'C:\temp\subnetory-audit' -AllowDestructiveTests
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$AdminUsername = 'admin',
    [Security.SecureString]$AdminPassword,
    [string]$AdminPasswordFile,
    [string]$OutputDirectory,
    [switch]$InitializeAdminIfRequired,
    [switch]$RequireNmapSuccess,
    [ValidateRange(0, 5000)]
    [int]$MinimumRequestIntervalMilliseconds = 0,
    [switch]$AllowDestructiveTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$BaseUrl = $BaseUrl.TrimEnd('/')
$runKey = Get-Date -Format 'yyyyMMdd-HHmmss'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\reports\api-audit-$runKey"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function ConvertFrom-SecureStringPlainText {
    param([Security.SecureString]$Value)

    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

if (-not [string]::IsNullOrWhiteSpace($AdminPasswordFile)) {
    $adminPasswordPlain = (Get-Content -Raw -LiteralPath $AdminPasswordFile).Trim()
} elseif ($null -ne $AdminPassword) {
    $adminPasswordPlain = ConvertFrom-SecureStringPlainText $AdminPassword
} else {
    $AdminPassword = Read-Host "Mot de passe de $AdminUsername" -AsSecureString
    $adminPasswordPlain = ConvertFrom-SecureStringPlainText $AdminPassword
}

$script:Results = [Collections.Generic.List[object]]::new()
$script:Findings = [Collections.Generic.List[object]]::new()
$script:AuditProbes = [Collections.Generic.List[object]]::new()
$script:CoveredOperations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$script:AdminToken = $null
$script:LastRequestStartedAt = $null

$handler = [Net.Http.HttpClientHandler]::new()
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(15)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Subnetory-API-Audit/1.0')

function New-RandomPassword {
    $bytes = [byte[]]::new(24)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ([Convert]::ToBase64String($bytes) + 'Aa1!')
}

function Send-HttpRequest {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [AllowNull()][object]$JsonBody,
        [string]$FilePath,
        [string]$FileFieldName = 'file',
        [string]$FileContentType = 'application/octet-stream'
    )

    if ($MinimumRequestIntervalMilliseconds -gt 0 -and $null -ne $script:LastRequestStartedAt) {
        $elapsedMilliseconds = ([DateTimeOffset]::UtcNow - $script:LastRequestStartedAt).TotalMilliseconds
        $remainingMilliseconds = $MinimumRequestIntervalMilliseconds - $elapsedMilliseconds
        if ($remainingMilliseconds -gt 0) {
            Start-Sleep -Milliseconds ([int][Math]::Ceiling($remainingMilliseconds))
        }
    }
    $script:LastRequestStartedAt = [DateTimeOffset]::UtcNow

    $uri = if ($Path.StartsWith('http://') -or $Path.StartsWith('https://')) {
        $Path
    } else {
        "$BaseUrl$Path"
    }
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $uri)
    try {
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        }
        if (-not [string]::IsNullOrWhiteSpace($FilePath)) {
            $multipart = [Net.Http.MultipartFormDataContent]::new()
            $fileBytes = [IO.File]::ReadAllBytes($FilePath)
            $fileContent = [Net.Http.ByteArrayContent]::new($fileBytes)
            $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new($FileContentType)
            $multipart.Add($fileContent, $FileFieldName, [IO.Path]::GetFileName($FilePath))
            $request.Content = $multipart
        } elseif ($PSBoundParameters.ContainsKey('JsonBody')) {
            $json = $JsonBody | ConvertTo-Json -Depth 30 -Compress
            $request.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        }

        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $stopwatch.Stop()
        $text = if ($bytes.Length -eq 0) { '' } else { [Text.Encoding]::UTF8.GetString($bytes) }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Bytes = $bytes
            Text = $text
            ContentType = if ($response.Content.Headers.ContentType) { $response.Content.Headers.ContentType.ToString() } else { '' }
            ContentDisposition = if ($response.Content.Headers.ContentDisposition) { $response.Content.Headers.ContentDisposition.ToString() } else { '' }
            DurationMs = $stopwatch.ElapsedMilliseconds
        }
    } finally {
        $request.Dispose()
    }
}

function Invoke-AuditRequest {
    param(
        [Parameter(Mandatory)][string]$Operation,
        [Parameter(Mandatory)][string]$Scenario,
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [AllowNull()][object]$JsonBody,
        [string]$FilePath,
        [string]$FileFieldName = 'file',
        [string]$FileContentType = 'application/octet-stream',
        [int[]]$ExpectedStatus = @(200),
        [switch]$Critical
    )

    $args = @{ Method = $Method; Path = $Path }
    if (-not [string]::IsNullOrWhiteSpace($Token)) { $args.Token = $Token }
    if ($PSBoundParameters.ContainsKey('JsonBody')) { $args.JsonBody = $JsonBody }
    if (-not [string]::IsNullOrWhiteSpace($FilePath)) {
        $args.FilePath = $FilePath
        $args.FileFieldName = $FileFieldName
        $args.FileContentType = $FileContentType
    }

    try {
        $response = Send-HttpRequest @args
        $passed = $response.StatusCode -in $ExpectedStatus
        $detail = if ($passed -or [string]::IsNullOrWhiteSpace($response.Text)) {
            ''
        } else {
            $response.Text.Substring(0, [Math]::Min(500, $response.Text.Length))
        }
    } catch {
        $response = [pscustomobject]@{ StatusCode = 0; Bytes = [byte[]]::new(0); Text = ''; ContentType = ''; ContentDisposition = ''; DurationMs = 0 }
        $passed = $false
        $detail = $_.Exception.Message
    }

    if ($Operation.StartsWith('/api/')) {
        throw "Clé d'opération invalide : $Operation"
    }
    if ($Operation -match '^\S+\s+/api/v1/') {
        [void]$script:CoveredOperations.Add($Operation)
    }
    $script:Results.Add([pscustomobject]@{
        Operation = $Operation
        Scenario = $Scenario
        Status = $response.StatusCode
        Expected = ($ExpectedStatus -join ',')
        Passed = $passed
        DurationMs = $response.DurationMs
        Detail = $detail
    })

    $mark = if ($passed) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} — {2} -> {3} (attendu {4})" -f $mark, $Operation, $Scenario, $response.StatusCode, ($ExpectedStatus -join '/')) -ForegroundColor $(if ($passed) { 'Green' } else { 'Red' })
    if ($Critical -and -not $passed) {
        throw "Scénario critique en échec : $Operation / $Scenario"
    }
    return $response
}

function ConvertFrom-ResponseJson {
    param([Parameter(Mandatory)]$Response)
    if ([string]::IsNullOrWhiteSpace($Response.Text)) { return $null }
    return $Response.Text | ConvertFrom-Json -Depth 100
}

function Add-Finding {
    param(
        [Parameter(Mandatory)][ValidateSet('P1','P2','P3')][string]$Priority,
        [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$Evidence
    )
    $duplicate = $script:Findings | Where-Object {
        $_.Priority -eq $Priority -and $_.Title -eq $Title -and $_.Evidence -eq $Evidence
    } | Select-Object -First 1
    if ($null -ne $duplicate) { return }

    $script:Findings.Add([pscustomobject]@{ Priority = $Priority; Title = $Title; Evidence = $Evidence })
    Write-Host "[CONSTAT $Priority] $Title" -ForegroundColor Yellow
}

function Get-AuditTotal {
    param([string]$EventType)

    # Avant le premier changement de mot de passe de l'administrateur, aucun
    # JWT ne permet encore de lire le journal. -1 signifie "mesure inconnue"
    # et déclenche une vérification différée sur le journal complet.
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { return -1 }

    $path = '/api/v1/admin/audit-log?page=0&size=1'
    if (-not [string]::IsNullOrWhiteSpace($EventType)) {
        $path += '&eventType=' + [Uri]::EscapeDataString($EventType)
    }
    $response = Send-HttpRequest -Method GET -Path $path -Token $script:AdminToken
    if ($response.StatusCode -ne 200) { return -1 }
    $page = ConvertFrom-ResponseJson $response

    # Compatibilite avec l'ancien PageImpl Spring et le PagedModel stable.
    if ($null -ne $page.PSObject.Properties['totalElements']) {
        return [int]$page.totalElements
    }
    if ($null -ne $page.PSObject.Properties['page'] -and
        $null -ne $page.page -and
        $null -ne $page.page.PSObject.Properties['totalElements']) {
        return [int]$page.page.totalElements
    }

    throw "Format de pagination inattendu pour GET /api/v1/admin/audit-log."
}

function Invoke-ProbedRequest {
    param(
        [Parameter(Mandatory)][string]$Operation,
        [Parameter(Mandatory)][string]$Scenario,
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [AllowNull()][object]$JsonBody,
        [string]$FilePath,
        [string]$FileContentType = 'application/octet-stream',
        [int[]]$ExpectedStatus = @(200),
        [Parameter(Mandatory)][string]$ExpectedAuditEvent,
        [string]$ExpectedAuditUsername,
        [switch]$Critical
    )

    # Compter l'evenement attendu plutot que le volume global : une mutation
    # n'est pas consideree journalisee parce qu'un LOGIN_SUCCESS concurrent
    # (ou tout autre evenement) a simplement fait augmenter le journal.
    $before = Get-AuditTotal -EventType $ExpectedAuditEvent
    $args = @{
        Operation = $Operation; Scenario = $Scenario; Method = $Method; Path = $Path
        ExpectedStatus = $ExpectedStatus; Critical = $Critical
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) { $args.Token = $Token }
    if ($PSBoundParameters.ContainsKey('JsonBody')) { $args.JsonBody = $JsonBody }
    if (-not [string]::IsNullOrWhiteSpace($FilePath)) {
        $args.FilePath = $FilePath
        $args.FileContentType = $FileContentType
    }
    $response = Invoke-AuditRequest @args
    $after = Get-AuditTotal -EventType $ExpectedAuditEvent
    $hasAudit = $before -ge 0 -and $after -gt $before
    $script:AuditProbes.Add([pscustomobject]@{
        Operation = $Operation
        Scenario = $Scenario
        ExpectedEvent = $ExpectedAuditEvent
        ExpectedUsername = $ExpectedAuditUsername
        Before = $before
        After = $after
        Delta = if ($before -ge 0 -and $after -ge 0) { $after - $before } else { -1 }
        Journalized = $hasAudit
    })
    return $response
}

function Get-Token {
    param(
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password,
        [string]$TotpCode,
        [int[]]$ExpectedStatus = @(200),
        [switch]$Critical,
        [string]$Scenario = 'Authentification valide'
    )
    $body = @{ username = $Username; password = $Password }
    if (-not [string]::IsNullOrWhiteSpace($TotpCode)) { $body.totpCode = $TotpCode }
    $response = Invoke-AuditRequest -Operation 'POST /api/v1/auth/token' -Scenario $Scenario -Method POST -Path '/api/v1/auth/token' -JsonBody $body -ExpectedStatus $ExpectedStatus -Critical:$Critical
    if ($response.StatusCode -ne 200) { return $null }
    return (ConvertFrom-ResponseJson $response).accessToken
}

function ConvertFrom-Base32 {
    param([Parameter(Mandatory)][string]$Value)
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    $clean = ($Value.ToUpperInvariant() -replace '[^A-Z2-7]', '')
    $output = [Collections.Generic.List[byte]]::new()
    $buffer = 0
    $bits = 0
    foreach ($char in $clean.ToCharArray()) {
        $index = $alphabet.IndexOf($char)
        if ($index -lt 0) { throw 'Secret TOTP Base32 invalide.' }
        $buffer = ($buffer -shl 5) -bor $index
        $bits += 5
        while ($bits -ge 8) {
            $bits -= 8
            $output.Add([byte](($buffer -shr $bits) -band 0xff))
            $buffer = $buffer -band ((1 -shl $bits) - 1)
        }
    }
    return $output.ToArray()
}

function New-TotpCode {
    param([Parameter(Mandatory)][string]$Secret)
    $counter = [uint64][Math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() / 30)
    $counterBytes = [BitConverter]::GetBytes($counter)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($counterBytes) }
    $hmac = [Security.Cryptography.HMACSHA1]::new((ConvertFrom-Base32 $Secret))
    try { $hash = $hmac.ComputeHash($counterBytes) } finally { $hmac.Dispose() }
    $offset = $hash[$hash.Length - 1] -band 0x0f
    $binary = (($hash[$offset] -band 0x7f) -shl 24) -bor
              (($hash[$offset + 1] -band 0xff) -shl 16) -bor
              (($hash[$offset + 2] -band 0xff) -shl 8) -bor
              ($hash[$offset + 3] -band 0xff)
    return ([int]($binary % 1000000)).ToString('D6')
}

function Initialize-LocalUser {
    param(
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$TemporaryPassword,
        [Parameter(Mandatory)][string]$PermanentPassword
    )

    [void](Get-Token -Username $Username -Password $TemporaryPassword -ExpectedStatus 403 -Scenario 'Mot de passe temporaire refusé avant changement')
    $response = Invoke-ProbedRequest -Operation 'POST /api/v1/auth/change-password-required' -Scenario "Premier mot de passe de $Username" -Method POST -Path '/api/v1/auth/change-password-required' -JsonBody @{
        username = $Username; currentPassword = $TemporaryPassword; newPassword = $PermanentPassword
    } -ExpectedStatus 204 -ExpectedAuditEvent 'PASSWORD_CHANGE' -ExpectedAuditUsername $Username -Critical
    $token = Get-Token -Username $Username -Password $PermanentPassword -Critical -Scenario "JWT immédiat de $Username"
    $probe = Send-HttpRequest -Method GET -Path '/api/v1/profile' -Token $token
    if ($probe.StatusCode -eq 401) {
        Add-Finding -Priority P1 -Title 'Un JWT émis immédiatement après un changement de mot de passe est inutilisable' -Evidence 'POST /auth/token retourne 200, puis le même JWT reçoit 401 jusquʼau changement de seconde, probablement à cause de la précision différente entre iat et not_before.'
        Start-Sleep -Milliseconds 1100
        $token = Get-Token -Username $Username -Password $PermanentPassword -Critical -Scenario "JWT après délai de précision pour $Username"
        $probe = Send-HttpRequest -Method GET -Path '/api/v1/profile' -Token $token
    }
    if ($probe.StatusCode -ne 200) {
        throw "Le JWT de $Username reste inutilisable après nouvelle émission : HTTP $($probe.StatusCode)."
    }
    return $token
}

try {
    Write-Host "Audit Subnetory : $BaseUrl" -ForegroundColor Cyan

    # Contrat et surfaces publiques
    $openApiResponse = Invoke-AuditRequest -Operation 'GET /v3/api-docs' -Scenario 'Contrat OpenAPI public' -Method GET -Path '/v3/api-docs' -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'openapi.json'), $openApiResponse.Bytes)
    $openApi = $openApiResponse.Text | ConvertFrom-Json -AsHashtable -Depth 100
    $expectedOperations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $nonApiOperations = [Collections.Generic.List[string]]::new()
    foreach ($path in $openApi.paths.Keys) {
        foreach ($method in $openApi.paths[$path].Keys) {
            if ($method -notin @('get','post','put','patch','delete')) { continue }
            $key = "$($method.ToUpperInvariant()) $path"
            if ($path.StartsWith('/api/v1/')) { [void]$expectedOperations.Add($key) }
            else { $nonApiOperations.Add($key) }
        }
    }
    if ($nonApiOperations.Count -gt 0) {
        Add-Finding -Priority P3 -Title 'Des routes Web sont publiées dans le contrat OpenAPI REST' -Evidence ($nonApiOperations -join ', ')
    }

    [void](Invoke-AuditRequest -Operation 'GET /actuator/health' -Scenario 'Healthcheck anonyme' -Method GET -Path '/actuator/health' -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /actuator/health/liveness' -Scenario 'Liveness anonyme' -Method GET -Path '/actuator/health/liveness' -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /actuator/health/readiness' -Scenario 'Readiness anonyme' -Method GET -Path '/actuator/health/readiness' -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/contexts' -Scenario 'Accès protégé sans JWT' -Method GET -Path '/api/v1/contexts' -ExpectedStatus 401)
    [void](Get-Token -Username $AdminUsername -Password (New-RandomPassword) -ExpectedStatus 401 -Scenario 'Mot de passe invalide')
    $script:AdminToken = Get-Token -Username $AdminUsername -Password $adminPasswordPlain `
        -ExpectedStatus @(200, 403) -Scenario 'Authentification administrateur' -Critical
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) {
        if (-not $InitializeAdminIfRequired) {
            throw 'Le compte administrateur exige probablement son premier changement de mot de passe. Relancer uniquement sur une base neuve avec -InitializeAdminIfRequired.'
        }
        $auditAdminPassword = New-RandomPassword
        $script:AdminToken = Initialize-LocalUser -Username $AdminUsername `
            -TemporaryPassword $adminPasswordPlain -PermanentPassword $auditAdminPassword
        $adminPasswordPlain = $auditAdminPassword
        $auditAdminPassword = $null
    }

    [void](Invoke-AuditRequest -Operation 'GET /actuator' -Scenario 'Actuator refusé sans JWT' -Method GET -Path '/actuator' -ExpectedStatus 401)
    [void](Invoke-AuditRequest -Operation 'GET /actuator' -Scenario 'Actuator autorisé à ADMIN' -Method GET -Path '/actuator' -Token $script:AdminToken -ExpectedStatus 200)
    [void](Invoke-AuditRequest -Operation 'GET /actuator/info' -Scenario 'Info Actuator à ADMIN' -Method GET -Path '/actuator/info' -Token $script:AdminToken -ExpectedStatus 200)

    # Contextes : lecture, création, consultation, modification, suppression jetable.
    $contextsResponse = Invoke-AuditRequest -Operation 'GET /api/v1/contexts' -Scenario 'Liste paginée des contextes' -Method GET -Path '/api/v1/contexts?page=0&size=200' -Token $script:AdminToken -ExpectedStatus 200 -Critical
    $seedContextNames = @('Siège', 'Datacenter', 'Agences')
    foreach ($name in $seedContextNames) {
        $existing = @((ConvertFrom-ResponseJson $contextsResponse).content) | Where-Object name -eq $name | Select-Object -First 1
        if ($null -eq $existing) {
            [void](Invoke-ProbedRequest -Operation 'POST /api/v1/contexts' -Scenario "Création du contexte $name" -Method POST -Path '/api/v1/contexts' -Token $script:AdminToken -JsonBody @{
                name = $name; description = "Contexte renseigné par l'audit API $runKey"
            } -ExpectedStatus 201 -ExpectedAuditEvent 'CONTEXT_CREATED' -Critical)
        }
    }
    $contextsResponse = Invoke-AuditRequest -Operation 'GET /api/v1/contexts' -Scenario 'Liste après enrichissement' -Method GET -Path '/api/v1/contexts?page=0&size=200' -Token $script:AdminToken -ExpectedStatus 200 -Critical
    $contexts = @((ConvertFrom-ResponseJson $contextsResponse).content)
    $firstManagedContext = $contexts | Where-Object name -eq 'Siège' | Select-Object -First 1
    if ($null -eq $firstManagedContext) { $firstManagedContext = $contexts[0] }
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/contexts/{id}' -Scenario 'Consultation par ID' -Method GET -Path "/api/v1/contexts/$($firstManagedContext.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    $updatedContext = Invoke-ProbedRequest -Operation 'PUT /api/v1/contexts/{id}' -Scenario 'Modification de description' -Method PUT -Path "/api/v1/contexts/$($firstManagedContext.id)" -Token $script:AdminToken -JsonBody @{
        name = $firstManagedContext.name; description = "Contexte validé par audit API $runKey"
    } -ExpectedStatus 200 -ExpectedAuditEvent 'CONTEXT_UPDATED' -Critical

    $throwawayContext = Invoke-ProbedRequest -Operation 'POST /api/v1/contexts' -Scenario 'Contexte jetable pour DELETE' -Method POST -Path '/api/v1/contexts' -Token $script:AdminToken -JsonBody @{
        name = "API-DELETE-$runKey"; description = 'Ressource jetable'
    } -ExpectedStatus 201 -ExpectedAuditEvent 'CONTEXT_CREATED' -Critical
    $throwawayContextId = (ConvertFrom-ResponseJson $throwawayContext).id
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/contexts/{id}' -Scenario 'Suppression du contexte jetable' -Method DELETE -Path "/api/v1/contexts/$throwawayContextId" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'CONTEXT_DELETED' -Critical)

    # Hiérarchie complète et au moins une IP dans chaque contexte.
    $resources = [Collections.Generic.List[object]]::new()
    $index = 0
    foreach ($context in $contexts) {
        $index++
        if ($index -gt 200) { throw 'Plus de 200 contextes : plage de test automatique insuffisante.' }
        $siteCode = ('API{0:D3}{1}' -f $index, ($runKey -replace '[^0-9]', '').Substring(6))
        if ($siteCode.Length -gt 20) { $siteCode = $siteCode.Substring(0, 20) }
        $site = Invoke-ProbedRequest -Operation 'POST /api/v1/sites' -Scenario "Site du contexte $($context.name)" -Method POST -Path '/api/v1/sites' -Token $script:AdminToken -JsonBody @{
            name = "Site principal — $($context.name)"; code = $siteCode; contextId = [long]$context.id
        } -ExpectedStatus 201 -ExpectedAuditEvent 'SITE_CREATED' -Critical
        $siteObject = ConvertFrom-ResponseJson $site

        $vlan = Invoke-ProbedRequest -Operation 'POST /api/v1/vlans' -Scenario "VLAN du contexte $($context.name)" -Method POST -Path '/api/v1/vlans' -Token $script:AdminToken -JsonBody @{
            vid = 2000 + $index; name = "VLAN utilisateurs $($context.name)"; siteId = [long]$siteObject.id
        } -ExpectedStatus 201 -ExpectedAuditEvent 'VLAN_CREATED' -Critical
        $vlanObject = ConvertFrom-ResponseJson $vlan

        $network = "10.240.$index.0/24"
        $subnet = Invoke-ProbedRequest -Operation 'POST /api/v1/subnets' -Scenario "Sous-réseau du contexte $($context.name)" -Method POST -Path '/api/v1/subnets' -Token $script:AdminToken -JsonBody @{
            network = $network; gateway = "10.240.$index.1"; description = "Réseau validé par audit API $runKey"
            contextId = [long]$context.id; siteId = [long]$siteObject.id; vlanId = [long]$vlanObject.id; parentId = $null
        } -ExpectedStatus 201 -ExpectedAuditEvent 'SUBNET_CREATED' -Critical
        $subnetObject = ConvertFrom-ResponseJson $subnet

        $address = Invoke-ProbedRequest -Operation 'POST /api/v1/addresses' -Scenario "Adresse du contexte $($context.name)" -Method POST -Path '/api/v1/addresses' -Token $script:AdminToken -JsonBody @{
            address = "10.240.$index.10"; mac = ('02:00:00:00:{0:X2}:{1:X2}' -f $index, 10)
            hostname = "host-$index-$runKey"; description = "Adresse assignée par audit API"
            subnetId = [long]$subnetObject.id; temporary = $false; discoverySource = 'api'
        } -ExpectedStatus 201 -ExpectedAuditEvent 'ADDRESS_CREATED' -Critical
        $addressObject = ConvertFrom-ResponseJson $address
        $resources.Add([pscustomobject]@{ Context = $context; Site = $siteObject; Vlan = $vlanObject; Subnet = $subnetObject; Address = $addressObject })
    }

    $primary = $resources[0]

    # Sites
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/sites' -Scenario 'Liste filtrée par contexte' -Method GET -Path "/api/v1/sites?contextId=$($primary.Context.id)&page=0&size=100" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/sites/{id}' -Scenario 'Consultation du site' -Method GET -Path "/api/v1/sites/$($primary.Site.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/sites/{id}' -Scenario 'Modification du site' -Method PUT -Path "/api/v1/sites/$($primary.Site.id)" -Token $script:AdminToken -JsonBody @{
        name = "$($primary.Site.name) — validé"; code = $primary.Site.code; contextId = [long]$primary.Context.id
    } -ExpectedStatus 200 -ExpectedAuditEvent 'SITE_UPDATED' -Critical)
    $deleteSite = Invoke-ProbedRequest -Operation 'POST /api/v1/sites' -Scenario 'Site jetable pour DELETE' -Method POST -Path '/api/v1/sites' -Token $script:AdminToken -JsonBody @{
        name = 'Site jetable'; code = ('DEL' + ($runKey -replace '[^0-9]', '').Substring(6)); contextId = [long]$primary.Context.id
    } -ExpectedStatus 201 -ExpectedAuditEvent 'SITE_CREATED' -Critical
    $deleteSiteId = (ConvertFrom-ResponseJson $deleteSite).id
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/sites/{id}' -Scenario 'Suppression du site jetable' -Method DELETE -Path "/api/v1/sites/$deleteSiteId" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'SITE_DELETED' -Critical)

    # VLAN
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/vlans' -Scenario 'Liste filtrée par site' -Method GET -Path "/api/v1/vlans?siteId=$($primary.Site.id)&page=0&size=100" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/vlans/{id}' -Scenario 'Consultation du VLAN' -Method GET -Path "/api/v1/vlans/$($primary.Vlan.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/vlans/{id}' -Scenario 'Modification du VLAN' -Method PUT -Path "/api/v1/vlans/$($primary.Vlan.id)" -Token $script:AdminToken -JsonBody @{
        vid = [int]$primary.Vlan.vid; name = "$($primary.Vlan.name) — validé"; siteId = [long]$primary.Site.id
    } -ExpectedStatus 200 -ExpectedAuditEvent 'VLAN_UPDATED' -Critical)
    $vlanZero = Invoke-AuditRequest -Operation 'POST /api/v1/vlans' -Scenario 'Refus attendu du VLAN 0' -Method POST -Path '/api/v1/vlans' -Token $script:AdminToken -JsonBody @{
        vid = 0; name = 'VLAN 0 invalide'; siteId = [long]$primary.Site.id
    } -ExpectedStatus 400
    if ($vlanZero.StatusCode -eq 201) {
        Add-Finding -Priority P1 -Title 'Le VLAN 0 est accepté par lʼAPI' -Evidence 'POST /api/v1/vlans avec vid=0 retourne 201 au lieu de 400.'
        $vlanZeroId = (ConvertFrom-ResponseJson $vlanZero).id
        [void](Invoke-AuditRequest -Operation 'DELETE /api/v1/vlans/{id}' -Scenario 'Nettoyage du VLAN 0 jetable' -Method DELETE -Path "/api/v1/vlans/$vlanZeroId" -Token $script:AdminToken -ExpectedStatus 204)
    }
    $deleteVlan = Invoke-ProbedRequest -Operation 'POST /api/v1/vlans' -Scenario 'VLAN jetable pour DELETE' -Method POST -Path '/api/v1/vlans' -Token $script:AdminToken -JsonBody @{
        vid = 3999; name = 'VLAN jetable'; siteId = [long]$primary.Site.id
    } -ExpectedStatus 201 -ExpectedAuditEvent 'VLAN_CREATED' -Critical
    $deleteVlanId = (ConvertFrom-ResponseJson $deleteVlan).id
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/vlans/{id}' -Scenario 'Suppression du VLAN jetable' -Method DELETE -Path "/api/v1/vlans/$deleteVlanId" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'VLAN_DELETED' -Critical)

    # Sous-réseaux
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/subnets' -Scenario 'Liste filtrée par contexte et site' -Method GET -Path "/api/v1/subnets?contextId=$($primary.Context.id)&siteId=$($primary.Site.id)&page=0&size=100" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/subnets/{id}' -Scenario 'Consultation du sous-réseau' -Method GET -Path "/api/v1/subnets/$($primary.Subnet.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/subnets/{id}' -Scenario 'Modification du sous-réseau' -Method PUT -Path "/api/v1/subnets/$($primary.Subnet.id)" -Token $script:AdminToken -JsonBody @{
        network = $primary.Subnet.network; gateway = $primary.Subnet.gateway; description = 'Sous-réseau modifié par audit API'
        contextId = [long]$primary.Context.id; siteId = [long]$primary.Site.id; vlanId = [long]$primary.Vlan.id; parentId = $null
    } -ExpectedStatus 200 -ExpectedAuditEvent 'SUBNET_UPDATED' -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/subnets/{id}/available-ips' -Scenario 'Suggestions dʼadresses disponibles' -Method GET -Path "/api/v1/subnets/$($primary.Subnet.id)/available-ips?count=5" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    $subnetCsv = Invoke-AuditRequest -Operation 'GET /api/v1/subnets/export/csv' -Scenario 'Export CSV des sous-réseaux' -Method GET -Path "/api/v1/subnets/export/csv?contextId=$($primary.Context.id)&siteId=$($primary.Site.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'subnets.csv'), $subnetCsv.Bytes)
    $subnetXlsx = Invoke-AuditRequest -Operation 'GET /api/v1/subnets/export/xlsx' -Scenario 'Export XLSX des sous-réseaux' -Method GET -Path "/api/v1/subnets/export/xlsx?contextId=$($primary.Context.id)&siteId=$($primary.Site.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'subnets.xlsx'), $subnetXlsx.Bytes)
    $deleteSubnet = Invoke-ProbedRequest -Operation 'POST /api/v1/subnets' -Scenario 'Sous-réseau jetable pour DELETE' -Method POST -Path '/api/v1/subnets' -Token $script:AdminToken -JsonBody @{
        network = '10.253.253.0/30'; gateway = '10.253.253.1'; description = 'Jetable'; contextId = [long]$primary.Context.id
        siteId = [long]$primary.Site.id; vlanId = $null; parentId = $null
    } -ExpectedStatus 201 -ExpectedAuditEvent 'SUBNET_CREATED' -Critical
    $deleteSubnetId = (ConvertFrom-ResponseJson $deleteSubnet).id

    # Un /30 sans resolution DNS valide le chemin Nmap complet rapidement,
    # meme si aucun hote ne repond sur le reseau Docker de l'audit.
    $scanCompletedBefore = Get-AuditTotal -EventType 'SUBNET_SCAN_COMPLETED'
    $scanFailedBefore = Get-AuditTotal -EventType 'SUBNET_SCAN_FAILED'
    $scan = Invoke-AuditRequest -Operation 'POST /api/v1/subnets/{id}/scan' -Scenario 'Scan Nmap contrôlé dʼun petit sous-réseau' -Method POST -Path "/api/v1/subnets/$deleteSubnetId/scan" -Token $script:AdminToken -JsonBody @{
        method = 'nmap'; override = $false; resolveDns = $false; arpPing = $false; timing = 'fast'; dnsServers = ''
    } -ExpectedStatus @(200,408,503)
    $scanExpectedEvent = if ($scan.StatusCode -eq 200) { 'SUBNET_SCAN_COMPLETED' } else { 'SUBNET_SCAN_FAILED' }
    $scanBefore = if ($scan.StatusCode -eq 200) { $scanCompletedBefore } else { $scanFailedBefore }
    $scanAfter = Get-AuditTotal -EventType $scanExpectedEvent
    $script:AuditProbes.Add([pscustomobject]@{
        Operation = 'POST /api/v1/subnets/{id}/scan'
        Scenario = 'Scan Nmap contrôlé dʼun petit sous-réseau'
        ExpectedEvent = $scanExpectedEvent
        ExpectedUsername = $AdminUsername
        Before = $scanBefore
        After = $scanAfter
        Delta = if ($scanBefore -ge 0 -and $scanAfter -ge 0) { $scanAfter - $scanBefore } else { -1 }
        Journalized = $scanBefore -ge 0 -and $scanAfter -gt $scanBefore
    })
    if ($scan.StatusCode -eq 503) {
        $priority = if ($RequireNmapSuccess) { 'P2' } else { 'P3' }
        Add-Finding -Priority $priority -Title 'Le scan Nmap nʼa pas pu être exécuté' -Evidence 'LʼAPI répond proprement 503 : vérifier que Nmap est présent dans lʼenvironnement de déploiement.'
    } elseif ($scan.StatusCode -eq 408) {
        $priority = if ($RequireNmapSuccess) { 'P2' } else { 'P3' }
        Add-Finding -Priority $priority -Title 'Le scan Nmap de contrôle a expiré' -Evidence 'Même le /30 rapide a atteint le délai configuré : vérifier la connectivité réseau et la politique de filtrage du conteneur.'
    }
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/subnets/{id}' -Scenario 'Suppression du sous-réseau jetable' -Method DELETE -Path "/api/v1/subnets/$deleteSubnetId" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'SUBNET_DELETED' -Critical)

    # Adresses : recherche, mises à jour, upserts, imports/exports et suppression.
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/addresses' -Scenario 'Liste et filtres combinés' -Method GET -Path "/api/v1/addresses?contextId=$($primary.Context.id)&subnetId=$($primary.Subnet.id)&hostnameContains=host&page=0&size=100" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/addresses/{id}' -Scenario 'Consultation par ID' -Method GET -Path "/api/v1/addresses/$($primary.Address.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/addresses/by-ip/{ip}' -Scenario 'Recherche par IP' -Method GET -Path "/api/v1/addresses/by-ip/$($primary.Address.address)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/addresses/by-hostname/{hostname}' -Scenario 'Recherche par hostname' -Method GET -Path "/api/v1/addresses/by-hostname/$([Uri]::EscapeDataString($primary.Address.hostname))" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/addresses/{id}' -Scenario 'Remplacement complet dʼune adresse' -Method PUT -Path "/api/v1/addresses/$($primary.Address.id)" -Token $script:AdminToken -JsonBody @{
        address = $primary.Address.address; mac = $primary.Address.mac; hostname = "$($primary.Address.hostname)-put"
        description = 'Adresse mise à jour par PUT'; subnetId = [long]$primary.Subnet.id; temporary = $false; discoverySource = 'api'
    } -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_UPDATED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'PATCH /api/v1/addresses/{id}' -Scenario 'Mise à jour partielle dʼune adresse' -Method PATCH -Path "/api/v1/addresses/$($primary.Address.id)" -Token $script:AdminToken -JsonBody @{
        description = 'Adresse mise à jour par PATCH'; temporary = $true
    } -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_UPDATED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/addresses/by-ip/{ip}' -Scenario 'Upsert par IP — création' -Method PUT -Path "/api/v1/addresses/by-ip/10.240.1.30?override=false" -Token $script:AdminToken -JsonBody @{
        subnetId = [long]$primary.Subnet.id; mac = '02:00:00:00:01:30'; hostname = "upsert-$runKey"; description = 'Créée par upsert'
        temporary = $false; discoverySource = 'api'
    } -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_UPSERTED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/addresses/bulk-upsert' -Scenario 'Upsert en masse' -Method POST -Path '/api/v1/addresses/bulk-upsert' -Token $script:AdminToken -JsonBody @{
        override = $true; addresses = @(
            @{ address = '10.240.1.40'; subnetId = [long]$primary.Subnet.id; mac = '02:00:00:00:01:40'; hostname = "bulk-40-$runKey"; description = 'Bulk'; temporary = $false; discoverySource = 'api' },
            @{ address = '10.240.1.41'; subnetId = [long]$primary.Subnet.id; mac = '02:00:00:00:01:41'; hostname = "bulk-41-$runKey"; description = 'Bulk'; temporary = $false; discoverySource = 'api' }
        )
    } -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_BULK_UPSERTED' -Critical)

    $csvGeneric = Join-Path $OutputDirectory 'addresses-generic.csv'
    $csvSpecific = Join-Path $OutputDirectory 'addresses-specific.csv'
    [IO.File]::WriteAllText($csvGeneric, "address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source`n10.240.1.50,$($primary.Subnet.id),,02:00:00:00:01:50,import-generic-$runKey,Import générique,false,csv`n", [Text.UTF8Encoding]::new($true))
    [IO.File]::WriteAllText($csvSpecific, "address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source`n10.240.1.51,$($primary.Subnet.id),,02:00:00:00:01:51,import-csv-$runKey,Import CSV,false,csv`n", [Text.UTF8Encoding]::new($true))
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/addresses/import' -Scenario 'Import avec détection automatique CSV' -Method POST -Path "/api/v1/addresses/import?contextId=$($primary.Context.id)&override=false" -Token $script:AdminToken -FilePath $csvGeneric -FileContentType 'text/csv' -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_IMPORT_COMPLETED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/addresses/import/csv' -Scenario 'Import CSV explicite' -Method POST -Path "/api/v1/addresses/import/csv?contextId=$($primary.Context.id)&override=false" -Token $script:AdminToken -FilePath $csvSpecific -FileContentType 'text/csv' -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_IMPORT_COMPLETED' -Critical)
    $addressCsv = Invoke-AuditRequest -Operation 'GET /api/v1/addresses/export/csv' -Scenario 'Export CSV filtré' -Method GET -Path "/api/v1/addresses/export/csv?contextId=$($primary.Context.id)&subnetId=$($primary.Subnet.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'addresses-export.csv'), $addressCsv.Bytes)
    $addressXlsx = Invoke-AuditRequest -Operation 'GET /api/v1/addresses/export/xlsx' -Scenario 'Export XLSX filtré' -Method GET -Path "/api/v1/addresses/export/xlsx?contextId=$($primary.Context.id)&subnetId=$($primary.Subnet.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical
    $addressXlsxPath = Join-Path $OutputDirectory 'addresses-export.xlsx'
    [IO.File]::WriteAllBytes($addressXlsxPath, $addressXlsx.Bytes)
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/addresses/import/xlsx' -Scenario 'Réimport XLSX idempotent' -Method POST -Path "/api/v1/addresses/import/xlsx?contextId=$($primary.Context.id)&override=false" -Token $script:AdminToken -FilePath $addressXlsxPath -FileContentType 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' -ExpectedStatus 200 -ExpectedAuditEvent 'ADDRESS_IMPORT_COMPLETED' -Critical)
    $deleteAddress = Invoke-ProbedRequest -Operation 'POST /api/v1/addresses' -Scenario 'Adresse jetable pour DELETE' -Method POST -Path '/api/v1/addresses' -Token $script:AdminToken -JsonBody @{
        address = '10.240.1.99'; subnetId = [long]$primary.Subnet.id; hostname = "delete-$runKey"; description = 'Jetable'; temporary = $true; discoverySource = 'api'
    } -ExpectedStatus 201 -ExpectedAuditEvent 'ADDRESS_CREATED' -Critical
    $deleteAddressId = (ConvertFrom-ResponseJson $deleteAddress).id
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/addresses/{id}' -Scenario 'Suppression de lʼadresse jetable' -Method DELETE -Path "/api/v1/addresses/$deleteAddressId" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'ADDRESS_DELETED' -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/dashboard' -Scenario 'Statistiques du contexte' -Method GET -Path "/api/v1/dashboard?contextId=$($primary.Context.id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)

    # Utilisateurs et matrice de rôles.
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/users/assignable-roles' -Scenario 'Rôles attribuables' -Method GET -Path '/api/v1/admin/users/assignable-roles' -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    $rolesResponse = Send-HttpRequest -Method GET -Path '/api/v1/admin/users/assignable-roles' -Token $script:AdminToken
    $roleMap = @{}
    foreach ($role in @(ConvertFrom-ResponseJson $rolesResponse)) { $roleMap[$role.name] = [long]$role.id }
    if ($roleMap.ContainsKey('ROLE_BACKUP')) {
        $backupRole = @(ConvertFrom-ResponseJson $rolesResponse) | Where-Object name -eq 'ROLE_BACKUP' | Select-Object -First 1
        if ($backupRole.description -eq 'Rôle non attribuable.') {
            Add-Finding -Priority P3 -Title 'La description OpenAPI du rôle BACKUP est contradictoire' -Evidence 'GET /admin/users/assignable-roles retourne ROLE_BACKUP mais le décrit comme « Rôle non attribuable. ».'
        }
    }

    $testUsers = [Collections.Generic.List[object]]::new()
    foreach ($definition in @(
        @{ Prefix = 'readonly'; Role = 'ROLE_READ_ONLY' },
        @{ Prefix = 'network'; Role = 'ROLE_NETWORK' },
        @{ Prefix = 'ip'; Role = 'ROLE_IP' },
        @{ Prefix = 'backup'; Role = 'ROLE_BACKUP' }
    )) {
        $username = "api-$($definition.Prefix)-$runKey"
        $temporaryPassword = New-RandomPassword
        $permanentPassword = New-RandomPassword
        $createdUser = Invoke-ProbedRequest -Operation 'POST /api/v1/admin/users' -Scenario "Création utilisateur $($definition.Role)" -Method POST -Path '/api/v1/admin/users' -Token $script:AdminToken -JsonBody @{
            username = $username; email = "$username@example.invalid"; temporaryPassword = $temporaryPassword; enabled = $true
            roleIds = @([long]$roleMap[$definition.Role]); contextIds = @([long]$primary.Context.id)
        } -ExpectedStatus 201 -ExpectedAuditEvent 'USER_CREATED' -Critical
        $userObject = ConvertFrom-ResponseJson $createdUser
        $token = Initialize-LocalUser -Username $username -TemporaryPassword $temporaryPassword -PermanentPassword $permanentPassword
        $testUsers.Add([pscustomobject]@{ Username = $username; Password = $permanentPassword; Role = $definition.Role; Id = [long]$userObject.id; Token = $token })
    }
    $readOnlyUser = $testUsers | Where-Object Role -eq 'ROLE_READ_ONLY' | Select-Object -First 1
    $networkUser = $testUsers | Where-Object Role -eq 'ROLE_NETWORK' | Select-Object -First 1
    $ipUser = $testUsers | Where-Object Role -eq 'ROLE_IP' | Select-Object -First 1
    $backupUser = $testUsers | Where-Object Role -eq 'ROLE_BACKUP' | Select-Object -First 1

    [void](Invoke-AuditRequest -Operation 'GET /api/v1/contexts' -Scenario 'Lecture autorisée READ_ONLY' -Method GET -Path '/api/v1/contexts?size=100' -Token $readOnlyUser.Token -ExpectedStatus 200)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/addresses' -Scenario 'Écriture refusée READ_ONLY' -Method POST -Path '/api/v1/addresses' -Token $readOnlyUser.Token -JsonBody @{
        address = '10.240.1.120'; subnetId = [long]$primary.Subnet.id; temporary = $false
    } -ExpectedStatus 403)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/sites' -Scenario 'Création autorisée NETWORK' -Method POST -Path '/api/v1/sites' -Token $networkUser.Token -JsonBody @{
        name = 'Site créé par NETWORK'; code = ('NET' + ($runKey -replace '[^0-9]', '').Substring(6)); contextId = [long]$primary.Context.id
    } -ExpectedStatus 201)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/addresses' -Scenario 'Adresse refusée à NETWORK sans rôle IP' -Method POST -Path '/api/v1/addresses' -Token $networkUser.Token -JsonBody @{
        address = '10.240.1.121'; subnetId = [long]$primary.Subnet.id; temporary = $false
    } -ExpectedStatus 403)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/addresses' -Scenario 'Adresse autorisée à IP' -Method POST -Path '/api/v1/addresses' -Token $ipUser.Token -JsonBody @{
        address = '10.240.1.122'; subnetId = [long]$primary.Subnet.id; hostname = "ip-role-$runKey"; temporary = $false; discoverySource = 'api'
    } -ExpectedStatus 201)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/subnets' -Scenario 'Sous-réseau refusé à IP sans rôle NETWORK' -Method POST -Path '/api/v1/subnets' -Token $ipUser.Token -JsonBody @{
        network = '10.252.252.0/30'; contextId = [long]$primary.Context.id; siteId = [long]$primary.Site.id
    } -ExpectedStatus 403)
    $otherContext = $resources | Where-Object { $_.Context.id -ne $primary.Context.id } | Select-Object -First 1
    if ($null -ne $otherContext) {
        [void](Invoke-AuditRequest -Operation 'GET /api/v1/sites/{id}' -Scenario 'Contexte hors périmètre refusé' -Method GET -Path "/api/v1/sites/$($otherContext.Site.id)" -Token $readOnlyUser.Token -ExpectedStatus 404)
    }
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/users' -Scenario 'Administration refusée à BACKUP' -Method GET -Path '/api/v1/admin/users' -Token $backupUser.Token -ExpectedStatus 403)

    # Cycle complet profil + MFA sur lʼutilisateur IP.
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/profile' -Scenario 'Profil utilisateur IP' -Method GET -Path '/api/v1/profile' -Token $ipUser.Token -ExpectedStatus 200 -Critical)
    $profilePassword2 = New-RandomPassword
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/profile/change-password' -Scenario 'Changement de mot de passe du profil' -Method POST -Path '/api/v1/profile/change-password' -Token $ipUser.Token -JsonBody @{
        currentPassword = $ipUser.Password; newPassword = $profilePassword2; confirmPassword = $profilePassword2
    } -ExpectedStatus 204 -ExpectedAuditEvent 'PASSWORD_CHANGE' -Critical)
    $ipUser.Password = $profilePassword2
    Start-Sleep -Milliseconds 1100
    $ipUser.Token = Get-Token -Username $ipUser.Username -Password $ipUser.Password -Critical -Scenario 'JWT après changement du mot de passe'

    $mfaSetup = Invoke-AuditRequest -Operation 'POST /api/v1/profile/mfa/setup' -Scenario 'Préparation MFA' -Method POST -Path '/api/v1/profile/mfa/setup' -Token $ipUser.Token -JsonBody @{} -ExpectedStatus 200 -Critical
    $mfaSetupObject = ConvertFrom-ResponseJson $mfaSetup
    $mfaCode = New-TotpCode $mfaSetupObject.secret
    $mfaEnable = Invoke-ProbedRequest -Operation 'POST /api/v1/profile/mfa/enable' -Scenario 'Activation MFA' -Method POST -Path '/api/v1/profile/mfa/enable' -Token $ipUser.Token -JsonBody @{
        secret = $mfaSetupObject.secret; code = $mfaCode
    } -ExpectedStatus 200 -ExpectedAuditEvent 'MFA_ENABLED' -Critical
    $recoveryCodes = @((ConvertFrom-ResponseJson $mfaEnable).recoveryCodes)
    [void](Get-Token -Username $ipUser.Username -Password $ipUser.Password -ExpectedStatus 401 -Scenario 'MFA exigé sans second facteur')
    $ipUser.Token = Get-Token -Username $ipUser.Username -Password $ipUser.Password -TotpCode $recoveryCodes[0] -Critical -Scenario 'JWT avec code de récupération MFA'
    $regenerated = Invoke-ProbedRequest -Operation 'POST /api/v1/profile/mfa/recovery-codes/regenerate' -Scenario 'Régénération des codes MFA' -Method POST -Path '/api/v1/profile/mfa/recovery-codes/regenerate' -Token $ipUser.Token -JsonBody @{
        code = $recoveryCodes[1]
    } -ExpectedStatus 200 -ExpectedAuditEvent 'MFA_RECOVERY_CODES_REGENERATED' -Critical
    $newRecoveryCodes = @((ConvertFrom-ResponseJson $regenerated).recoveryCodes)
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/profile/mfa/disable' -Scenario 'Désactivation MFA par le titulaire' -Method POST -Path '/api/v1/profile/mfa/disable' -Token $ipUser.Token -JsonBody @{
        currentPassword = $ipUser.Password; code = $newRecoveryCodes[0]
    } -ExpectedStatus 204 -ExpectedAuditEvent 'MFA_DISABLED' -Critical)
    $mfaSetup2 = Invoke-AuditRequest -Operation 'POST /api/v1/profile/mfa/setup' -Scenario 'Second enrôlement MFA pour anti-lockout admin' -Method POST -Path '/api/v1/profile/mfa/setup' -Token $ipUser.Token -JsonBody @{} -ExpectedStatus 200 -Critical
    $mfaSetupObject2 = ConvertFrom-ResponseJson $mfaSetup2
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/profile/mfa/enable' -Scenario 'Réactivation MFA' -Method POST -Path '/api/v1/profile/mfa/enable' -Token $ipUser.Token -JsonBody @{
        secret = $mfaSetupObject2.secret; code = (New-TotpCode $mfaSetupObject2.secret)
    } -ExpectedStatus 200 -ExpectedAuditEvent 'MFA_ENABLED' -Critical)

    # Toutes les opérations Admin Users.
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/users' -Scenario 'Liste des utilisateurs' -Method GET -Path '/api/v1/admin/users?page=0&size=100' -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/users/{id}' -Scenario 'Détail utilisateur IP' -Method GET -Path "/api/v1/admin/users/$($ipUser.Id)" -Token $script:AdminToken -ExpectedStatus 200 -Critical)
    [void](Invoke-ProbedRequest -Operation 'PATCH /api/v1/admin/users/{id}/roles' -Scenario 'Ajout du rôle READ_ONLY' -Method PATCH -Path "/api/v1/admin/users/$($ipUser.Id)/roles" -Token $script:AdminToken -JsonBody @{
        roleIds = @([long]$roleMap['ROLE_IP'], [long]$roleMap['ROLE_READ_ONLY'])
    } -ExpectedStatus 200 -ExpectedAuditEvent 'USER_ROLES_UPDATED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'PATCH /api/v1/admin/users/{id}/contexts' -Scenario 'Mise à jour des contextes autorisés' -Method PATCH -Path "/api/v1/admin/users/$($ipUser.Id)/contexts" -Token $script:AdminToken -JsonBody @{
        contextIds = @([long]$primary.Context.id)
    } -ExpectedStatus 200 -ExpectedAuditEvent 'USER_CONTEXTS_UPDATED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/admin/users/{id}/disable-mfa' -Scenario 'Désactivation MFA par un administrateur' -Method POST -Path "/api/v1/admin/users/$($ipUser.Id)/disable-mfa" -Token $script:AdminToken -JsonBody @{} -ExpectedStatus 204 -ExpectedAuditEvent 'MFA_DISABLED_BY_ADMIN' -Critical)
    [void](Invoke-ProbedRequest -Operation 'PATCH /api/v1/admin/users/{id}/disable' -Scenario 'Désactivation utilisateur' -Method PATCH -Path "/api/v1/admin/users/$($ipUser.Id)/disable" -Token $script:AdminToken -JsonBody @{} -ExpectedStatus 200 -ExpectedAuditEvent 'USER_DISABLED' -Critical)
    [void](Invoke-ProbedRequest -Operation 'PATCH /api/v1/admin/users/{id}/enable' -Scenario 'Réactivation utilisateur' -Method PATCH -Path "/api/v1/admin/users/$($ipUser.Id)/enable" -Token $script:AdminToken -JsonBody @{} -ExpectedStatus 200 -ExpectedAuditEvent 'USER_ENABLED' -Critical)
    $resetPassword = New-RandomPassword
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/admin/users/{id}/reset-password' -Scenario 'Réinitialisation de mot de passe' -Method POST -Path "/api/v1/admin/users/$($ipUser.Id)/reset-password" -Token $script:AdminToken -JsonBody @{
        newPassword = $resetPassword
    } -ExpectedStatus 204 -ExpectedAuditEvent 'ADMIN_PASSWORD_RESET' -Critical)
    $resetPermanent = New-RandomPassword
    $ipUser.Token = Initialize-LocalUser -Username $ipUser.Username -TemporaryPassword $resetPassword -PermanentPassword $resetPermanent
    $ipUser.Password = $resetPermanent
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/admin/users/{id}/invalidate-tokens' -Scenario 'Invalidation administrateur des JWT' -Method POST -Path "/api/v1/admin/users/$($ipUser.Id)/invalidate-tokens" -Token $script:AdminToken -JsonBody @{} -ExpectedStatus 204 -ExpectedAuditEvent 'TOKENS_INVALIDATED' -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/profile' -Scenario 'Ancien JWT refusé après invalidation' -Method GET -Path '/api/v1/profile' -Token $ipUser.Token -ExpectedStatus 401)
    Start-Sleep -Milliseconds 1100
    $ipUser.Token = Get-Token -Username $ipUser.Username -Password $ipUser.Password -Critical -Scenario 'Nouveau JWT après invalidation'
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/auth/logout-all' -Scenario 'Déconnexion de tous les JWT' -Method POST -Path '/api/v1/auth/logout-all' -Token $ipUser.Token -JsonBody @{} -ExpectedStatus 204 -ExpectedAuditEvent 'TOKENS_INVALIDATED' -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/profile' -Scenario 'JWT refusé après logout-all' -Method GET -Path '/api/v1/profile' -Token $ipUser.Token -ExpectedStatus 401)

    $logoutToken = Get-Token -Username $AdminUsername -Password $adminPasswordPlain -Critical -Scenario 'JWT administrateur dédié au logout'
    [void](Invoke-ProbedRequest -Operation 'POST /api/v1/auth/logout' -Scenario 'Révocation du JWT courant' -Method POST -Path '/api/v1/auth/logout' -Token $logoutToken -JsonBody @{} -ExpectedStatus 204 -ExpectedAuditEvent 'TOKEN_REVOKED' -Critical)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/auth/logout' -Scenario 'Logout idempotent du même JWT' -Method POST -Path '/api/v1/auth/logout' -Token $logoutToken -JsonBody @{} -ExpectedStatus 204)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/profile' -Scenario 'JWT révoqué refusé' -Method GET -Path '/api/v1/profile' -Token $logoutToken -ExpectedStatus 401)

    # LDAP : configuration isolée puis restauration de la configuration initiale.
    $ldapOriginal = Invoke-AuditRequest -Operation 'GET /api/v1/admin/ldap' -Scenario 'Lecture configuration LDAP' -Method GET -Path '/api/v1/admin/ldap' -Token $script:AdminToken -ExpectedStatus 200 -Critical
    if ($AllowDestructiveTests) {
        [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/admin/ldap' -Scenario 'Configuration LDAP locale non joignable' -Method PUT -Path '/api/v1/admin/ldap' -Token $script:AdminToken -JsonBody @{
            enabled = $false; url = 'ldap://127.0.0.1:9'; baseDn = 'dc=example,dc=invalid'; userSearchBase = 'ou=users'
            userSearchFilter = '(sAMAccountName={0})'; managerDn = ''; managerPassword = ''; clearManagerPassword = $true
            defaultRoles = @('ROLE_READ_ONLY'); defaultRole = 'ROLE_READ_ONLY'
        } -ExpectedStatus 200 -ExpectedAuditEvent 'LDAP_CONFIGURATION_UPDATED' -Critical)
        [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/ldap/test-connection' -Scenario 'Diagnostic LDAP non joignable maîtrisé' -Method POST -Path '/api/v1/admin/ldap/test-connection' -Token $script:AdminToken -JsonBody @{} -ExpectedStatus 200 -Critical)
        [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/ldap/test-user' -Scenario 'Recherche LDAP non joignable maîtrisée' -Method POST -Path '/api/v1/admin/ldap/test-user' -Token $script:AdminToken -JsonBody @{ username = 'audit-user' } -ExpectedStatus 200 -Critical)
        $ldap = ConvertFrom-ResponseJson $ldapOriginal
        [void](Invoke-AuditRequest -Operation 'PUT /api/v1/admin/ldap' -Scenario 'Restauration configuration LDAP' -Method PUT -Path '/api/v1/admin/ldap' -Token $script:AdminToken -JsonBody @{
            enabled = [bool]$ldap.enabled; url = $ldap.url; baseDn = $ldap.baseDn; userSearchBase = $ldap.userSearchBase
            userSearchFilter = $ldap.userSearchFilter; managerDn = ''; managerPassword = ''; clearManagerPassword = $true
            defaultRoles = @($ldap.defaultRoles); defaultRole = $ldap.defaultRole
        } -ExpectedStatus 200 -Critical)
    } else {
        foreach ($operation in @('PUT /api/v1/admin/ldap','POST /api/v1/admin/ldap/test-connection','POST /api/v1/admin/ldap/test-user')) {
            Add-Finding -Priority P3 -Title "Test non exécuté sans -AllowDestructiveTests : $operation" -Evidence 'La configuration LDAP ne doit pas être modifiée par défaut sur une instance réelle.'
        }
    }

    # Sauvegardes : rôle BACKUP, téléchargement, import et restauration sur instance isolée.
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup' -Scenario 'Lecture configuration par BACKUP' -Method GET -Path '/api/v1/admin/backup' -Token $backupUser.Token -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup/runs' -Scenario 'Historique par BACKUP' -Method GET -Path '/api/v1/admin/backup/runs?page=0&size=100' -Token $backupUser.Token -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup/restores' -Scenario 'Historique restaurations par BACKUP' -Method GET -Path '/api/v1/admin/backup/restores?page=0&size=100' -Token $backupUser.Token -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'PUT /api/v1/admin/backup' -Scenario 'Configuration refusée à BACKUP' -Method PUT -Path '/api/v1/admin/backup' -Token $backupUser.Token -JsonBody @{ enabled = $false; cronExpression = '0 0 2 * * *'; retentionCount = 20 } -ExpectedStatus 403)
    [void](Invoke-ProbedRequest -Operation 'PUT /api/v1/admin/backup' -Scenario 'Configuration par ADMIN' -Method PUT -Path '/api/v1/admin/backup' -Token $script:AdminToken -JsonBody @{ enabled = $false; cronExpression = '0 0 2 * * *'; retentionCount = 20 } -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_SETTINGS_UPDATED' -Critical)

    $backupRun = Invoke-ProbedRequest -Operation 'POST /api/v1/admin/backup/trigger' -Scenario 'Sauvegarde manuelle par BACKUP' -Method POST -Path '/api/v1/admin/backup/trigger' -Token $backupUser.Token -JsonBody @{ label = "Audit API $runKey" } -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_TRIGGERED' -Critical
    $backupRunObject = ConvertFrom-ResponseJson $backupRun
    $backupDownload = Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup/runs/{id}/download' -Scenario 'Téléchargement par BACKUP' -Method GET -Path "/api/v1/admin/backup/runs/$($backupRunObject.id)/download" -Token $backupUser.Token -ExpectedStatus 200 -Critical
    $backupFile = Join-Path $OutputDirectory $backupRunObject.fileName
    [IO.File]::WriteAllBytes($backupFile, $backupDownload.Bytes)
    [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup/runs/{id}/linked-restores' -Scenario 'Liens avant restauration' -Method GET -Path "/api/v1/admin/backup/runs/$($backupRunObject.id)/linked-restores" -Token $backupUser.Token -ExpectedStatus 200 -Critical)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/backup/import' -Scenario 'Import refusé à BACKUP' -Method POST -Path '/api/v1/admin/backup/import' -Token $backupUser.Token -FilePath $backupFile -ExpectedStatus 403)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/backup/restore' -Scenario 'Restauration refusée à BACKUP' -Method POST -Path '/api/v1/admin/backup/restore' -Token $backupUser.Token -JsonBody @{ backupRunId = [long]$backupRunObject.id; confirmationText = $backupRunObject.fileName } -ExpectedStatus 403)
    [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/backup/purge' -Scenario 'Purge refusée à BACKUP' -Method POST -Path '/api/v1/admin/backup/purge' -Token $backupUser.Token -JsonBody @{ beforeDate = '1970-01-01' } -ExpectedStatus 403)
    [void](Invoke-AuditRequest -Operation 'DELETE /api/v1/admin/backup/runs/{id}' -Scenario 'Suppression refusée à BACKUP' -Method DELETE -Path "/api/v1/admin/backup/runs/$($backupRunObject.id)" -Token $backupUser.Token -ExpectedStatus 403)

    if ($AllowDestructiveTests) {
        $importedBackup = Invoke-ProbedRequest -Operation 'POST /api/v1/admin/backup/import' -Scenario 'Import dʼun dump téléchargé' -Method POST -Path '/api/v1/admin/backup/import' -Token $script:AdminToken -FilePath $backupFile -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_IMPORTED' -Critical
        $importedBackupObject = ConvertFrom-ResponseJson $importedBackup
        [void](Invoke-AuditRequest -Operation 'POST /api/v1/admin/backup/restore' -Scenario 'Confirmation de restauration incorrecte' -Method POST -Path '/api/v1/admin/backup/restore' -Token $script:AdminToken -JsonBody @{
            backupRunId = [long]$importedBackupObject.id; confirmationText = 'CONFIRMATION-INCORRECTE'
        } -ExpectedStatus 409)
        [void](Invoke-ProbedRequest -Operation 'POST /api/v1/admin/backup/restore' -Scenario 'Restauration réelle sur base isolée' -Method POST -Path '/api/v1/admin/backup/restore' -Token $script:AdminToken -JsonBody @{
            backupRunId = [long]$importedBackupObject.id; confirmationText = $importedBackupObject.fileName
        } -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_RESTORED' -Critical)
        Start-Sleep -Milliseconds 1100
        $script:AdminToken = Get-Token -Username $AdminUsername -Password $adminPasswordPlain -Critical -Scenario 'JWT après restauration'
        [void](Invoke-AuditRequest -Operation 'GET /api/v1/admin/backup/restores' -Scenario 'Historique après restauration' -Method GET -Path '/api/v1/admin/backup/restores?page=0&size=100' -Token $script:AdminToken -ExpectedStatus 200 -Critical)
        $deleteRun = Invoke-ProbedRequest -Operation 'POST /api/v1/admin/backup/trigger' -Scenario 'Sauvegarde jetable pour DELETE' -Method POST -Path '/api/v1/admin/backup/trigger' -Token $script:AdminToken -JsonBody @{ label = 'Jetable après restauration' } -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_TRIGGERED' -Critical
        $deleteRunObject = ConvertFrom-ResponseJson $deleteRun
        [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/admin/backup/runs/{id}' -Scenario 'Suppression de sauvegarde jetable' -Method DELETE -Path "/api/v1/admin/backup/runs/$($deleteRunObject.id)?cascade=false" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'BACKUP_DELETED' -Critical)
        [void](Invoke-ProbedRequest -Operation 'POST /api/v1/admin/backup/purge' -Scenario 'Purge sans candidat ancien' -Method POST -Path '/api/v1/admin/backup/purge' -Token $script:AdminToken -JsonBody @{ beforeDate = '1970-01-01' } -ExpectedStatus 200 -ExpectedAuditEvent 'BACKUP_PURGED' -Critical)
    } else {
        foreach ($operation in @('POST /api/v1/admin/backup/import','POST /api/v1/admin/backup/restore','DELETE /api/v1/admin/backup/runs/{id}','POST /api/v1/admin/backup/purge')) {
            Add-Finding -Priority P3 -Title "Test non exécuté sans -AllowDestructiveTests : $operation" -Evidence 'Ces opérations modifient ou suppriment des sauvegardes.'
        }
    }

    # Suppression dʼun utilisateur jetable et sauvegarde des preuves dʼaudit.
    [void](Invoke-ProbedRequest -Operation 'DELETE /api/v1/admin/users/{id}' -Scenario 'Suppression dʼun utilisateur de test' -Method DELETE -Path "/api/v1/admin/users/$($readOnlyUser.Id)" -Token $script:AdminToken -ExpectedStatus 204 -ExpectedAuditEvent 'USER_DELETED' -Critical)

    $auditList = Invoke-AuditRequest -Operation 'GET /api/v1/admin/audit-log' -Scenario 'Lecture du journal complet' -Method GET -Path '/api/v1/admin/audit-log?page=0&size=1000' -Token $script:AdminToken -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'audit-log.json'), $auditList.Text, [Text.UTF8Encoding]::new($false))
    $auditEvents = @((ConvertFrom-ResponseJson $auditList).content)
    foreach ($probe in $script:AuditProbes | Where-Object { $_.Delta -lt 0 }) {
        $matchingEvents = @($auditEvents | Where-Object eventType -eq $probe.ExpectedEvent)
        if (-not [string]::IsNullOrWhiteSpace($probe.ExpectedUsername)) {
            $matchingEvents = @($matchingEvents | Where-Object {
                $_.username -eq $probe.ExpectedUsername -or
                $_.targetUsername -eq $probe.ExpectedUsername
            })
        }
        if ($matchingEvents.Count -gt 0) {
            $probe.Journalized = $true
        }
    }
    $auditCsv = Invoke-AuditRequest -Operation 'GET /api/v1/admin/audit-log/export.csv' -Scenario 'Export CSV du journal' -Method GET -Path '/api/v1/admin/audit-log/export.csv' -Token $script:AdminToken -ExpectedStatus 200 -Critical
    [IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'audit-log.csv'), $auditCsv.Bytes)

    foreach ($probe in $script:AuditProbes | Where-Object { -not $_.Journalized }) {
        Add-Finding -Priority P2 -Title "Mutation non journalisée : $($probe.Operation)" -Evidence "$($probe.Scenario) nʼa ajouté aucune entrée dʼaudit (delta=$($probe.Delta)); événement attendu : $($probe.ExpectedEvent)."
    }

    if ($AllowDestructiveTests) {
        $beforePurge = Get-AuditTotal
        $auditPurge = Invoke-AuditRequest -Operation 'POST /api/v1/admin/audit-log/purge' -Scenario 'Purge réelle du journal isolé' -Method POST -Path '/api/v1/admin/audit-log/purge' -Token $script:AdminToken -JsonBody @{
            beforeDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
        } -ExpectedStatus 200 -Critical
        $afterPurge = Get-AuditTotal
        if ($beforePurge -le 0 -or $afterPurge -ne 1) {
            Add-Finding -Priority P2 -Title 'La purge du journal dʼaudit nʼa pas produit le résultat attendu' -Evidence "Avant=$beforePurge, après=$afterPurge, réponse=$($auditPurge.Text)"
        }
        $purgeEvidence = Send-HttpRequest -Method GET -Path '/api/v1/admin/audit-log?eventType=AUDIT_LOG_PURGED&page=0&size=10' -Token $script:AdminToken
        $purgeEvents = if ($purgeEvidence.StatusCode -eq 200) { @((ConvertFrom-ResponseJson $purgeEvidence).content) } else { @() }
        if ($purgeEvents.Count -ne 1) {
            Add-Finding -Priority P2 -Title 'La purge du journal dʼaudit nʼest elle-même pas journalisée' -Evidence 'Aucun événement AUDIT_LOG_PURGED unique nʼest visible après la purge totale.'
        }
    } else {
        Add-Finding -Priority P3 -Title 'Test non exécuté sans -AllowDestructiveTests : POST /api/v1/admin/audit-log/purge' -Evidence 'La purge détruit définitivement des événements dʼaudit.'
    }

    $missingOperations = @($expectedOperations | Where-Object { -not $script:CoveredOperations.Contains($_) } | Sort-Object)
    foreach ($operation in $missingOperations) {
        Add-Finding -Priority P2 -Title "Route API non couverte par le script : $operation" -Evidence 'Lʼopération figure dans OpenAPI mais aucun scénario nʼa été exécuté.'
    }

    # Contradictions de contrat constatées pendant lʼinventaire.
    $upsertDescription = $openApi.paths['/api/v1/addresses/by-ip/{ip}']['put']['description']
    $upsertResponses = @($openApi.paths['/api/v1/addresses/by-ip/{ip}']['put']['responses'].Keys)
    if ($upsertDescription -match '201' -and '201' -notin $upsertResponses) {
        Add-Finding -Priority P3 -Title 'Contrat contradictoire pour lʼupsert dʼadresse' -Evidence 'La description annonce 201 à la création, mais le contrôleur et OpenAPI ne publient que 200.'
    }

    $resultsPath = Join-Path $OutputDirectory 'api-results.json'
    $findingsPath = Join-Path $OutputDirectory 'api-findings.json'
    $probesPath = Join-Path $OutputDirectory 'audit-probes.json'
    $script:Results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultsPath -Encoding utf8NoBOM
    $script:Findings | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $findingsPath -Encoding utf8NoBOM
    $script:AuditProbes | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $probesPath -Encoding utf8NoBOM

    $passedCount = @($script:Results | Where-Object Passed).Count
    $failedCount = @($script:Results | Where-Object { -not $_.Passed }).Count
    $coveredCount = @($expectedOperations | Where-Object { $script:CoveredOperations.Contains($_) }).Count
    $report = [Text.StringBuilder]::new()
    $tick = [char]0x60
    [void]$report.AppendLine('# Rapport dʼaudit API Subnetory')
    [void]$report.AppendLine()
    [void]$report.AppendLine("- Date : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    [void]$report.AppendLine("- Cible : $tick$BaseUrl$tick")
    [void]$report.AppendLine("- Contrat : $($expectedOperations.Count) opérations ${tick}/api/v1$tick")
    [void]$report.AppendLine("- Couverture : $coveredCount/$($expectedOperations.Count)")
    [void]$report.AppendLine("- Scénarios réussis : $passedCount")
    [void]$report.AppendLine("- Scénarios en échec : $failedCount")
    [void]$report.AppendLine("- Constats : $($script:Findings.Count)")
    [void]$report.AppendLine()
    [void]$report.AppendLine('## Constats')
    [void]$report.AppendLine()
    if ($script:Findings.Count -eq 0) {
        [void]$report.AppendLine('- Aucun constat.')
    } else {
        foreach ($finding in $script:Findings | Sort-Object Priority,Title -Unique) {
            [void]$report.AppendLine("- **[$($finding.Priority)] $($finding.Title)** — $($finding.Evidence)")
        }
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine('## Scénarios en échec')
    [void]$report.AppendLine()
    $failed = @($script:Results | Where-Object { -not $_.Passed })
    if ($failed.Count -eq 0) {
        [void]$report.AppendLine('- Aucun.')
    } else {
        foreach ($item in $failed) {
            [void]$report.AppendLine("- $tick$($item.Operation)$tick — $($item.Scenario) : HTTP $($item.Status), attendu $($item.Expected). $($item.Detail)")
        }
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine('## Journalisation des mutations')
    [void]$report.AppendLine()
    [void]$report.AppendLine('| Opération | Scénario | Événement attendu | Delta | Journalisée |')
    [void]$report.AppendLine('|---|---|---:|---:|:---:|')
    foreach ($probe in $script:AuditProbes) {
        $journalized = if ($probe.Journalized) { 'oui' } else { 'non' }
        [void]$report.AppendLine("| $tick$($probe.Operation)$tick | $($probe.Scenario) | $tick$($probe.ExpectedEvent)$tick | $($probe.Delta) | $journalized |")
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine('## Fichiers de preuve')
    [void]$report.AppendLine()
    [void]$report.AppendLine('- `api-results.json` : résultat de chaque appel.')
    [void]$report.AppendLine('- `audit-probes.json` : delta du journal autour de chaque mutation.')
    [void]$report.AppendLine('- `audit-log.json` et `audit-log.csv` : journal capturé avant la purge de test.')
    [void]$report.AppendLine('- `openapi.json` : contrat effectivement testé.')
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'report.md'), $report.ToString(), [Text.UTF8Encoding]::new($false))

    Write-Host "AUDIT_COMPLETE output=$OutputDirectory coverage=$coveredCount/$($expectedOperations.Count) passed=$passedCount failed=$failedCount findings=$($script:Findings.Count)" -ForegroundColor Cyan
    if ($missingOperations.Count -gt 0) { exit 2 }
    if ($failedCount -gt 0) { exit 1 }
    if (@($script:Findings | Where-Object { $_.Priority -in @('P1', 'P2') }).Count -gt 0) { exit 3 }
    exit 0
} finally {
    $adminPasswordPlain = $null
    $script:AdminToken = $null
    $client.Dispose()
    $handler.Dispose()
}
