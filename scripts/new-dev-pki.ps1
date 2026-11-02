param([Parameter(Mandatory)][string]$OutputDirectory, [string]$Keytool = 'keytool')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$target = [IO.Path]::GetFullPath($OutputDirectory)
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\','/')
if (!$([IO.Path]::IsPathRooted($OutputDirectory)) -or $target.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $target -eq $workspace) {
    throw 'PKI output must be an absolute operator directory outside the workspace.'
}
if (Test-Path -LiteralPath $target) { throw 'Output already exists; refusing to replace keys.' }
if (!$env:DEV_PKI_PASSWORD -or $env:DEV_PKI_PASSWORD.Length -lt 24) { throw 'Supply DEV_PKI_PASSWORD through local env (at least 24 characters); never as a command argument.' }
New-Item -ItemType Directory -Path $target | Out-Null
function Invoke-Keytool([string[]]$Arguments) {
    # No command/output echo: keytool errors can contain operator paths.
    & $Keytool @Arguments -storetype PKCS12 -storepass:env DEV_PKI_PASSWORD *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Keytool failed; partial output preserved for operator inspection.' }
}
function File([string]$Name) { Join-Path $target $Name }
Invoke-Keytool @('-genkeypair','-alias','ca','-dname','CN=HomeCare-Local-Workload-CA','-keyalg','RSA','-keysize','3072','-ext','bc=ca:true','-ext','KU=keyCertSign,cRLSign','-validity','30','-keystore',(File 'ca.p12'))
Invoke-Keytool @('-exportcert','-alias','ca','-rfc','-keystore',(File 'ca.p12'),'-file',(File 'ca.pem'))
Invoke-Keytool @('-importcert','-noprompt','-alias','ca','-file',(File 'ca.pem'),'-keystore',(File 'trust.p12'))
foreach ($name in @('identity','gateway','rogue')) {
    $eku = if ($name -eq 'identity') { 'EKU=serverAuth' } else { 'EKU=serverAuth,clientAuth' }
    Invoke-Keytool @('-genkeypair','-alias',$name,'-dname',"CN=$name",'-keyalg','RSA','-keysize','3072','-validity','7','-keystore',(File "$name.p12"))
    Invoke-Keytool @('-certreq','-alias',$name,'-keystore',(File "$name.p12"),'-file',(File "$name.csr"))
    Invoke-Keytool @('-gencert','-alias','ca','-keystore',(File 'ca.p12'),'-infile',(File "$name.csr"),'-outfile',(File "$name.pem"),'-rfc','-validity','7','-ext',"SAN=DNS:localhost,DNS:$name,IP:127.0.0.1",'-ext',$eku,'-ext','KU=digitalSignature,keyEncipherment')
    Invoke-Keytool @('-importcert','-noprompt','-alias','ca','-file',(File 'ca.pem'),'-keystore',(File "$name.p12"))
    Invoke-Keytool @('-importcert','-alias',$name,'-file',(File "$name.pem"),'-keystore',(File "$name.p12"))
}
Write-Output 'Dev PKI created outside workspace. Protect directory ACLs; never mount ca.p12 into applications.'
