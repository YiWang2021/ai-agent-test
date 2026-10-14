param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$IncludeModel,
    [string]$OutputDirectory = 'target/evaluation-results'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$adminKey = $env:SUPPORT_ADMIN_API_KEY
if (-not $adminKey -and (Test-Path -LiteralPath '.env.local')) {
    Get-Content -LiteralPath '.env.local' | ForEach-Object {
        if ($_ -match '^SUPPORT_ADMIN_API_KEY=(.+)$') { $adminKey = $matches[1] }
    }
}
if (-not $adminKey) { throw 'Set SUPPORT_ADMIN_API_KEY or run configure-local.ps1 first.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$modes = @('retrieval-baseline', 'retrieval')
if ($IncludeModel) {
    Write-Output 'Agent/tools evaluations call the configured responder. With ai enabled, fixture questions, retrieved knowledge and tool results go to the external model and incur API charges.'
    $modes += @('agent', 'tools')
}
foreach ($mode in $modes) {
    $report = Invoke-RestMethod "$($BaseUrl.TrimEnd('/'))/api/admin/evaluations/run?mode=$mode&limit=50" `
        -Method Post -Headers @{'X-API-Key'=$adminKey} -TimeoutSec 1800
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory "$mode.json") -Encoding utf8
    $report | Select-Object mode,total,successful,successRate,averageLatencyMs,p95LatencyMs,meanReciprocalRank,responder,embeddingModel
}
