# Regenerate the workspace-local debug keystore used by :app signing.
# Usage: powershell -File tools/new-debug-keystore.ps1
$ErrorActionPreference = 'Stop'
$ks = Join-Path $PSScriptRoot '..\android\debug.keystore'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { $keytool = 'keytool' }
& $keytool -genkeypair -keystore $ks -storepass android -keypass android `
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=Android Debug,O=Android,C=US"
Write-Host "wrote $ks"
