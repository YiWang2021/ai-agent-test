$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$credentialFile = Join-Path $projectRoot '.env.production.local'
$settings = @{}
if (Test-Path -LiteralPath $credentialFile) {
    Get-Content -LiteralPath $credentialFile | ForEach-Object {
        if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]]=$matches[2] }
    }
}
foreach ($name in @('SUPPORT_API_KEY','SUPPORT_ADMIN_API_KEY','SUPPORT_OTHER_USER_API_KEY')) {
    if (-not $settings[$name]) {
        $bytes=[byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $settings[$name]=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    }
}
if (-not $settings['SUPPORT_CONCURRENCY_NAMESPACE']) { $settings['SUPPORT_CONCURRENCY_NAMESPACE']='support-production' }
$settings.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key)=$($_.Value)" } |
    Set-Content -LiteralPath $credentialFile -Encoding utf8
Write-Output 'Production credentials prepared in git-ignored .env.production.local. Existing credentials preserved; no values printed.'
Write-Output 'All application instances sharing one model quota MUST use the same concurrency namespace and limits.'
