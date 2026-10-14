$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$configPath = Join-Path (Get-Location) '.env.vm'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw 'Missing .env.vm containing VM_BIND_IP, DB_PASSWORD and REDIS_PASSWORD.'
}
$settings = @{}
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
}
foreach ($key in @('VM_BIND_IP', 'DB_PASSWORD', 'REDIS_PASSWORD')) {
    if (-not $settings[$key]) { throw "Missing $key in .env.vm" }
}
$env:SPRING_PROFILES_ACTIVE = 'postgres,redis'
$env:DB_URL = "jdbc:postgresql://$($settings['VM_BIND_IP']):5432/support_agent"
$env:DB_USER = 'support'
$env:DB_PASSWORD = $settings['DB_PASSWORD']
$env:REDIS_HOST = $settings['VM_BIND_IP']
$env:REDIS_PORT = '6379'
$env:SPRING_DATA_REDIS_PASSWORD = $settings['REDIS_PASSWORD']
& mvn -s maven-settings.xml spring-boot:run
exit $LASTEXITCODE
