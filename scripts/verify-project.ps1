param([switch]$WithRedis, [switch]$WithSemantic, [switch]$WithPostgres)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
. (Join-Path $PSScriptRoot 'use-project-java.ps1')
if ($WithRedis -or $WithPostgres) {
    $configuration = Join-Path $projectRoot '.idea/runConfigurations/SupportAgent_VMware.xml'
    if (Test-Path -LiteralPath $configuration) {
        [xml]$document = Get-Content -LiteralPath $configuration
        foreach ($entry in $document.SelectNodes('//env')) {
            if (($WithRedis -and $entry.name -match '^(REDIS_|SPRING_DATA_REDIS_)') -or ($WithPostgres -and $entry.name -match '^DB_')) { [Environment]::SetEnvironmentVariable($entry.name,$entry.value,'Process') }
        }
    }
}
$env:RUN_REDIS_TESTS = "$($WithRedis.IsPresent)".ToLowerInvariant()
$env:RUN_SEMANTIC_TESTS = "$($WithSemantic.IsPresent)".ToLowerInvariant()
$env:RUN_POSTGRES_TESTS = "$($WithPostgres.IsPresent)".ToLowerInvariant()
& mvn -s maven-settings.xml test
exit $LASTEXITCODE
