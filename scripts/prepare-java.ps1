$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $projectRoot 'data/runtime'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive = Join-Path $directory 'temurin17.zip'
$url = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$expected = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest $url -OutFile "$archive.partial" -TimeoutSec 300
    Move-Item -LiteralPath "$archive.partial" -Destination $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'JDK archive checksum mismatch' }
$jdk = Join-Path $directory 'jdk-17.0.20.1+1'
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe'))) { Expand-Archive -LiteralPath $archive -DestinationPath $directory }
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe'))) { throw 'Unexpected JDK archive layout' }
[System.IO.File]::WriteAllText((Join-Path $directory 'java-home.txt'), $jdk)
Write-Output 'Project-local JDK 17 is ready. System JAVA_HOME and installed Java were not modified.'
