<#
.SYNOPSIS
  Infrastructure checkpoint (tasks.md task 3) for Windows / PowerShell.
.DESCRIPTION
  Brings up the one-command stack with the checkpoint profile, then verifies:
    - Keycloak issues a JWT via the mini-app direct access grant,
    - Kong rejects an unauthenticated request (401),
    - Kong routes an authenticated request to the health stub (200),
    - Kong returns 404 for an unknown route.
  Compatible with Windows PowerShell 5.1 and PowerShell 7+.
.PARAMETER TearDown
  Stop the stack after verification instead of leaving it running.
.PARAMETER KeepKeycloak
  Skip resetting the Keycloak data volume. By default the volume is reset so the
  declarative realm (including the seeded demo users) is always (re)imported.
#>
[CmdletBinding()]
param([switch]$TearDown, [switch]$KeepKeycloak)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

function Env-Or($name, $default) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$compose   = @('compose', '--profile', 'checkpoint')
$kongProxy = "http://localhost:$(Env-Or 'KONG_PROXY_PORT' '8000')"
$keycloak  = "http://localhost:$(Env-Or 'KEYCLOAK_PORT' '8080')"
$realm     = Env-Or 'KEYCLOAK_REALM' 'dynamic-pricing'
$demoUser  = Env-Or 'DEMO_USER' 'demo.customer'
$demoPass  = Env-Or 'DEMO_PASS' 'demo_customer_dev_only'

function Pass($m) { Write-Host "  PASS $m" -ForegroundColor Green }
function Info($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "  FAIL $m" -ForegroundColor Red; exit 1 }

function Wait-Healthy($name) {
  $deadline = (Get-Date).AddSeconds(300)
  while ((Get-Date) -lt $deadline) {
    $status = docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $name 2>$null
    if ($status -eq 'healthy') { Pass "$name is healthy"; return }
    if (-not $status)          { Fail "$name container not found" }
    Start-Sleep -Seconds 5
  }
  Fail "$name did not become healthy within 300s"
}

# Returns the HTTP status code for a GET, without throwing on 4xx/5xx (PS 5.1 safe).
function Get-HttpCode($url, $headers) {
  try {
    $r = Invoke-WebRequest -Uri $url -Headers $headers -Method Get -UseBasicParsing
    return [int]$r.StatusCode
  } catch [System.Net.WebException] {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    return -1
  } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
    return -1
  }
}

try {
  if (-not $KeepKeycloak) {
    Info 'Resetting Keycloak data volume to force realm import'
    & docker @compose rm -fsv keycloak 2>$null | Out-Null
    $project = Env-Or 'COMPOSE_PROJECT_NAME' 'dynamic-pricing-platform'
    docker volume rm "${project}_keycloak_data" 2>$null | Out-Null
  }

  Info 'Starting full stack with checkpoint profile (docker-compose up)'
  & docker @compose up -d

  Info 'Waiting for infrastructure health'
  Wait-Healthy 'dpp-keycloak'
  Wait-Healthy 'dpp-kong'
  Wait-Healthy 'dpp-health-stub'

  Info 'Verifying Keycloak token issuance'
  $body = @{
    grant_type = 'password'; client_id = 'mini-app'
    username   = $demoUser;  password  = $demoPass
  }
  $tok = Invoke-RestMethod -Method Post `
    -Uri "$keycloak/realms/$realm/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' -Body $body
  if (-not $tok.access_token) { Fail 'Keycloak did not return an access_token' }
  Pass "Keycloak issued a JWT for $demoUser"
  $auth = @{ Authorization = "Bearer $($tok.access_token)" }

  Info 'Verifying Kong gateway routing'
  $code = Get-HttpCode "$kongProxy/customers/anything" @{}
  if ($code -eq 401) { Pass 'No JWT -> 401' } else { Fail "No JWT expected 401, got $code" }

  $code = Get-HttpCode "$kongProxy/customers/anything" $auth
  if ($code -eq 200) { Pass 'Valid JWT -> 200 via stub' } else { Fail "Valid JWT expected 200, got $code" }

  $code = Get-HttpCode "$kongProxy/no-such-route" $auth
  if ($code -eq 404) { Pass 'Unknown route -> 404' } else { Fail "Unknown route expected 404, got $code" }

  Info 'Infrastructure checkpoint PASSED'
}
finally {
  if ($TearDown) {
    Info 'Tearing down stack'
    & docker @compose down
  }
}
