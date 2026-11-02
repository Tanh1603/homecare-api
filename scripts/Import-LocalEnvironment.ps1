param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\','/')
if (![IO.Path]::IsPathRooted($Path)) { throw 'Use an absolute operator env-file path outside the workspace.' }
$fullPath = [IO.Path]::GetFullPath($Path)
if ($fullPath.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Operator env files must stay outside the workspace.'
}
try { $lines = [IO.File]::ReadAllLines($fullPath) } catch { throw 'Cannot read operator env file (path suppressed).' }
$values = @{}
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid env syntax; expected NAME=value (line/value suppressed).' }
    $key = $Matches[1]; $value = $Matches[2]
    if ($values.ContainsKey($key)) { throw "Duplicate env name: $key" }
    # Literal values, no Invoke-Expression, interpolation, expansion or logging.
    $values[$key] = $value
}
foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process') }
Write-Output "Loaded $($values.Count) operator variables into this process; values suppressed."
