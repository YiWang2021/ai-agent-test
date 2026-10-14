param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start-local.ps1') -Port $Port -Profiles 'ai,postgres,redis,semantic,production'
exit $LASTEXITCODE
