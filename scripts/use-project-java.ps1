$javaHomeFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'data/runtime/java-home.txt'
if (Test-Path -LiteralPath $javaHomeFile) {
    $projectJava = (Get-Content -LiteralPath $javaHomeFile -Raw).Trim()
    $localCandidate = Join-Path (Split-Path $javaHomeFile -Parent) (Split-Path $projectJava -Leaf)
    if (Test-Path -LiteralPath (Join-Path $localCandidate 'bin/java.exe')) {
        $projectJava = $localCandidate
        Set-Content -LiteralPath $javaHomeFile -Value $projectJava -Encoding utf8
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectJava 'bin/java.exe'))) {
        $runtimeRoot = Split-Path $javaHomeFile -Parent
        $relocated = Get-ChildItem -LiteralPath $runtimeRoot -Directory -Filter 'jdk-*' | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
        if (-not $relocated) { throw 'Project JDK not found; run prepare-java.ps1' }
        $projectJava = $relocated.FullName
        Set-Content -LiteralPath $javaHomeFile -Value $projectJava -Encoding utf8
    }
    $env:JAVA_HOME = $projectJava
    $env:PATH = (Join-Path $projectJava 'bin') + [IO.Path]::PathSeparator + $env:PATH
}
