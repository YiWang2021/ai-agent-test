$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localFile = Join-Path $projectRoot '.env.local'
$settings = @{}
if (Test-Path -LiteralPath $localFile) {
    Get-Content -LiteralPath $localFile | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
}
foreach ($name in @('SUPPORT_ADMIN_API_KEY','SUPPORT_OTHER_USER_API_KEY')) {
    if (-not $settings[$name]) {
        $bytes = [byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        $settings[$name] = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    }
}
$settings.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath $localFile -Encoding utf8
$configuration = Join-Path $projectRoot '.idea/runConfigurations/SupportAgent_VMware.xml'
if (Test-Path -LiteralPath $configuration) {
    [xml]$document = Get-Content -LiteralPath $configuration
    $envs = $document.SelectSingleNode('//envs')
    if (-not $envs) { throw 'Existing IDEA configuration has no envs element.' }
    $settings['SPRING_PROFILES_ACTIVE'] = 'ai,postgres,redis,semantic'
    foreach ($name in @('SUPPORT_ADMIN_API_KEY','SUPPORT_OTHER_USER_API_KEY','SPRING_PROFILES_ACTIVE')) {
        $node = $document.SelectSingleNode("//env[@name='$name']")
        if (-not $node) { $node = $document.CreateElement('env'); $node.SetAttribute('name',$name); [void]$envs.AppendChild($node) }
        $node.SetAttribute('value',$settings[$name])
    }
    $document.Save($configuration)
    $javaHomeFile = Join-Path $projectRoot 'data/runtime/java-home.txt'
    if (Test-Path -LiteralPath $javaHomeFile) {
        foreach ($name in @('ALTERNATIVE_JRE_PATH','ALTERNATIVE_JRE_PATH_ENABLED')) {
            $option = $document.SelectSingleNode("//configuration/option[@name='$name']")
            if (-not $option) {
                $option = $document.CreateElement('option'); $option.SetAttribute('name',$name)
                [void]$document.SelectSingleNode('//configuration').AppendChild($option)
            }
            $value = if ($name -eq 'ALTERNATIVE_JRE_PATH') { (Get-Content -LiteralPath $javaHomeFile -Raw).Trim() } else { 'true' }
            $option.SetAttribute('value',$value)
        }
        $document.Save($configuration)
    }
    Write-Output 'IDEA VMware configuration now uses ai,postgres,redis,semantic. Existing model/database credentials were preserved.'
}
Write-Output 'Local account credentials are stored in .env.local (git-ignored). No credentials were printed.'
