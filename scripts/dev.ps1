param(
    [Parameter(Mandatory)][ValidateSet('init','up','provision','migrate','run','test','down')][string]$Action,
    [ValidateSet('gateway','identity','household','provider','booking','commerce','review','notification')][string]$Service = 'identity',
    [ValidatePattern('^hc_(dev|test_[a-z0-9]{1,20})$')][string]$DatabasePrefix = 'hc_dev',
    [int]$Port = 8080
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$apiRoot = Split-Path $PSScriptRoot -Parent
$owners = @('identity','household','provider','booking','commerce','review','notification')
$previousEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $previousEnvironment[$_.Name] = $_.Value }
function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "External command failed (exit $LASTEXITCODE). No checks were skipped." }
}
function New-LocalPassword {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
Push-Location $apiRoot
try {
    if ($Action -eq 'init') {
        if (Test-Path -LiteralPath '.env') { throw '.env exists; preserving it.' }
        $lines = @('POSTGRES_PORT=55432', "POSTGRES_PASSWORD=$(New-LocalPassword)",
            'RABBITMQ_URL=amqp://127.0.0.1:55672', 'RABBITMQ_PORT=55672', 'RABBITMQ_USERNAME=homecare_dev',
            "RABBITMQ_PASSWORD=$(New-LocalPassword)", 'RABBITMQ_VHOST=homecare')
        foreach ($owner in $owners) {
            $lines += "$($owner.ToUpper())_DB_PASSWORD=$(New-LocalPassword)"
            $lines += "$($owner.ToUpper())_MIGRATOR_PASSWORD=$(New-LocalPassword)"
        }
        [IO.File]::WriteAllLines((Join-Path $apiRoot '.env'), $lines)
        Write-Host 'Generated ignored .env. Credentials are not printed.'
        return
    }
    if (!(Test-Path -LiteralPath '.env')) { throw 'Run scripts/dev.ps1 init first, or supply a local .env.' }
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $apiRoot '.env'))) {
        if ($line -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
    if (!$env:RABBITMQ_URL) { $env:RABBITMQ_URL = "amqp://$($env:RABBITMQ_HOST):$($env:RABBITMQ_PORT)" }
    switch ($Action) {
        'up' { Invoke-Checked { docker compose up -d --wait --wait-timeout 180 postgres rabbitmq } }
        'down' { Invoke-Checked { docker compose --profile '*' down }; Write-Host 'Database volume preserved.' }
        'provision' {
            # Each invocation creates NEW databases. Existing names fail; never reset existing data.
            Invoke-Checked { docker compose cp scripts/provision.sql postgres:/tmp/homecare-provision.sql }
            foreach ($owner in $owners) {
                $db = "${DatabasePrefix}_${owner}"
                $runtimePassword = [Environment]::GetEnvironmentVariable("$($owner.ToUpper())_DB_PASSWORD")
                $migratorPassword = [Environment]::GetEnvironmentVariable("$($owner.ToUpper())_MIGRATOR_PASSWORD")
                if (!$runtimePassword -or !$migratorPassword) { throw "Missing credentials for $owner" }
                if ($runtimePassword -notmatch '^[A-Za-z0-9_-]{32,128}$' -or $migratorPassword -notmatch '^[A-Za-z0-9_-]{32,128}$') {
                    throw 'Provisioning requires generated base64url passwords (32..128 characters); run init for a fresh environment.'
                }
                # Secrets travel on stdin; no password command-line arguments.
                $inputSql = "\set database $db`n\set runtime ${db}_app`n\set migrator ${db}_migrator`n" +
                    "\set runtime_password '$($runtimePassword.Replace("'","''"))'`n" +
                    "\set migrator_password '$($migratorPassword.Replace("'","''"))'`n\i /tmp/homecare-provision.sql"
                $inputSql | docker compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1
                if ($LASTEXITCODE -ne 0) { throw "Provisioning failed for $db; existing data preserved." }
            }
        }
        'migrate' {
            foreach ($owner in $owners) {
                $env:LIQUIBASE_COMMAND_URL = "jdbc:postgresql://127.0.0.1:$($env:POSTGRES_PORT)/${DatabasePrefix}_${owner}"
                $env:LIQUIBASE_COMMAND_USERNAME = "${DatabasePrefix}_${owner}_migrator"
                $env:LIQUIBASE_COMMAND_PASSWORD = [Environment]::GetEnvironmentVariable("$($owner.ToUpper())_MIGRATOR_PASSWORD")
                $env:LIQUIBASE_LIQUIBASE_SCHEMA_NAME = 'migration'
                Invoke-Checked { .\gradlew.bat ":services:${owner}-service:migrate" }
            }
        }
        'run' {
            if ($Service -ne 'gateway') {
                if (!$env:DATABASE_URL) {
                    $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($env:POSTGRES_PORT)/${DatabasePrefix}_${Service}"
                }
                $env:DB_USERNAME = "${DatabasePrefix}_${Service}_app"
                $env:DB_PASSWORD = [Environment]::GetEnvironmentVariable("$($Service.ToUpper())_DB_PASSWORD")
            }
            if ($Service -in @('identity','gateway')) {
                $env:TLS_KEYSTORE = [Environment]::GetEnvironmentVariable("$($Service.ToUpper())_TLS_KEYSTORE")
                $env:TLS_KEYSTORE_PASSWORD = [Environment]::GetEnvironmentVariable("$($Service.ToUpper())_TLS_KEYSTORE_PASSWORD")
                if (!$env:TLS_KEYSTORE -or !$env:TLS_KEYSTORE_PASSWORD -or !$env:TLS_TRUSTSTORE -or !$env:TLS_TRUSTSTORE_PASSWORD -or !$env:FIREBASE_PROJECT_ID) {
                    throw 'Phase3 run requires service keystore, truststore, Firebase project and ADC outside source. Use test for the signed local fixture.'
                }
            }
            $env:SERVER_PORT = "$Port"
            Invoke-Checked { .\gradlew.bat ":services:${Service}-service:bootRun" }
        }
        'test' {
            if ($DatabasePrefix -notmatch '^hc_test_[a-z0-9]+$') { throw 'Tests require a dedicated hc_test_<run> prefix.' }
            $env:TEST_DATABASE_PREFIX = $DatabasePrefix
            $testTasks = @('test','integrationTest','bootJar')
            if ($PSBoundParameters.ContainsKey('Service')) {
                $testTasks = @("${Service}-service") | ForEach-Object { ":services:${_}:test"; ":services:${_}:integrationTest"; ":services:${_}:bootJar" }
            }
            Invoke-Checked { .\gradlew.bat @testTasks }
        }
    }
} finally {
    # Do not leave credentials injected into an interactive caller's environment.
    foreach ($entry in @(Get-ChildItem Env:)) {
        if (!$previousEnvironment.ContainsKey($entry.Name)) { [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process') }
    }
    foreach ($key in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    Pop-Location
}
