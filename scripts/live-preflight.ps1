# Presence/shape only; never print environment values, paths, tokens or file contents.
$ErrorActionPreference = 'Stop'
$required = @('FIREBASE_PROJECT_ID','GOOGLE_APPLICATION_CREDENTIALS','FIREBASE_WEB_API_KEY',
    'FIREBASE_TEST_UID','FIREBASE_TEST_EMAIL','FIREBASE_TEST_PASSWORD',
    'GATEWAY_TLS_KEYSTORE','GATEWAY_TLS_KEYSTORE_PASSWORD','IDENTITY_TLS_KEYSTORE','IDENTITY_TLS_KEYSTORE_PASSWORD',
    'TLS_TRUSTSTORE','TLS_TRUSTSTORE_PASSWORD','GATEWAY_URL','IDENTITY_URL')
$missing = @($required | Where-Object { $v = [Environment]::GetEnvironmentVariable($_); !$v -or $v -eq 'PLACEHOLDER' })
if ($null -ne [Environment]::GetEnvironmentVariable('FIREBASE_AUTH_EMULATOR_HOST')) { throw 'FAIL: emulator env must be absent.' }
if ($missing.Count) { Write-Output ('BLOCKED missing env names: ' + ($missing -join ', ')); exit 2 }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\','/')
foreach ($key in @('GOOGLE_APPLICATION_CREDENTIALS','GATEWAY_TLS_KEYSTORE','IDENTITY_TLS_KEYSTORE','TLS_TRUSTSTORE')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($value.StartsWith('file:')) { $value = ([Uri]$value).LocalPath }
    if (![IO.Path]::IsPathRooted($value) -or !(Test-Path -LiteralPath $value -PathType Leaf)) { throw "FAIL: invalid external file for $key" }
    if ([IO.Path]::GetFullPath($value).StartsWith($workspace + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw "FAIL: $key must refer outside workspace" }
}
try { $adc = Get-Content -LiteralPath $env:GOOGLE_APPLICATION_CREDENTIALS -Raw | ConvertFrom-Json } catch { throw 'FAIL: ADC unreadable (details suppressed)' }
if ($adc.type -ne 'service_account' -or $adc.project_id -ne $env:FIREBASE_PROJECT_ID) { throw 'FAIL: ADC type/project mismatch' }
Write-Output 'PASS: presence/path/project preflight only; Google connectivity and runtime are not yet verified.'
