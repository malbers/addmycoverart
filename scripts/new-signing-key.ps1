# Creates the AddMyCoverArt release signing key, once. Run it yourself; it asks for a password.
#
#   powershell -ExecutionPolicy Bypass -File scripts\new-signing-key.ps1
#
# Writes two files to ~\.claude\.secrets\ (never the repo):
#   addmycoverart-release.jks              the key
#   addmycoverart-signing.properties       what Gradle reads to sign release builds
#
# Back up BOTH files (and the password) somewhere safe, e.g. 1Password. Every future update
# must be signed with this same key, or people have to uninstall before they can update.

$ErrorActionPreference = 'Stop'
$secrets = Join-Path $HOME '.claude\.secrets'
$jks = Join-Path $secrets 'addmycoverart-release.jks'
$props = Join-Path $secrets 'addmycoverart-signing.properties'

if ((Test-Path $jks) -or (Test-Path $props)) {
    Write-Host 'A release key already exists. Not touching it: replacing a key breaks updates for everyone who installed the app.'
    exit 1
}

$keytool = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'bin\keytool.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) { throw 'keytool not found; install Microsoft OpenJDK 17' }

$a = Read-Host 'Choose a password for the release key (8+ characters)' -AsSecureString
$b = Read-Host 'Type it again' -AsSecureString
$pa = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($a))
$pb = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($b))
if ($pa -ne $pb) { throw 'The two passwords differ. Nothing was created.' }
if ($pa.Length -lt 8) { throw 'Use at least 8 characters. Nothing was created.' }

New-Item -ItemType Directory -Force -Path $secrets | Out-Null
# Hand the password to keytool through an environment variable of this process only, never the command line.
$env:AMCA_KEY_PW = $pa
try {
    & $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias addmycoverart -keyalg RSA -keysize 4096 `
        -validity 10000 -dname 'CN=AddMyCoverArt, O=malbers' -storepass:env AMCA_KEY_PW -keypass:env AMCA_KEY_PW | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
} finally {
    Remove-Item Env:AMCA_KEY_PW -ErrorAction SilentlyContinue
}

$jksForward = $jks -replace '\\', '/'
@(
    "storeFile=$jksForward"
    "storePassword=$pa"
    'keyAlias=addmycoverart'
    "keyPassword=$pa"
) | Set-Content -Encoding ascii $props

Write-Host ''
Write-Host 'Release key created (password not shown).'
Write-Host "  $jks"
Write-Host "  $props"
Write-Host 'Back up both files and the password now.'
