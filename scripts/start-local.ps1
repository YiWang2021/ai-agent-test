param([int]$Port = 8080, [string]$Profiles = 'ai,postgres,redis,semantic')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
. (Join-Path $PSScriptRoot 'use-project-java.ps1')
# Reuse the user's existing IDEA connection settings; never print their values.
$configuration = Join-Path $projectRoot '.idea/runConfigurations/SupportAgent_VMware.xml'
if (Test-Path -LiteralPath $configuration) {
    [xml]$document = Get-Content -LiteralPath $configuration
    foreach ($entry in $document.SelectNodes('//env')) {
        if ($entry.name -match '^(DEEPSEEK_|DB_|REDIS_|SPRING_DATA_REDIS_|SUPPORT_)') {
            [Environment]::SetEnvironmentVariable($entry.name, $entry.value, 'Process')
        }
    }
}
if (Test-Path -LiteralPath '.env.local') {
    Get-Content -LiteralPath '.env.local' | ForEach-Object {
        if ($_ -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
    }
}
if ($Profiles.Split(',') -contains 'production' -and (Test-Path -LiteralPath '.env.production.local')) {
    Get-Content -LiteralPath '.env.production.local' | ForEach-Object {
        if ($_ -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
    }
}
if ($Profiles.Split(',') -contains 'ai' -and -not $env:DEEPSEEK_API_KEY) { throw 'DEEPSEEK_API_KEY is required for the ai profile.' }
if ($Profiles.Split(',') -contains 'semantic' -and -not (Test-Path -LiteralPath 'data/models/multilingual-minilm/model.onnx')) {
    throw 'Run ./scripts/prepare-embedding.ps1 first.'
}
$env:SERVER_PORT = "$Port"
$env:SPRING_PROFILES_ACTIVE = $Profiles
& mvn -s maven-settings.xml spring-boot:run
exit $LASTEXITCODE
