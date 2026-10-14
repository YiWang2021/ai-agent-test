$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $projectRoot 'data/models/multilingual-minilm'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$repository = 'sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2'
$revision = 'e8f8c211226b894fcb81acc59f3b34ba3efd5f42'
$metadata = Invoke-RestMethod "https://huggingface.co/api/models/$repository/revision/${revision}?blobs=true" -TimeoutSec 30
$artifacts = @{'onnx/model_quint8_avx2.onnx'='model.onnx'; 'tokenizer.json'='tokenizer.json'}
$manifest = @()
foreach ($source in $artifacts.Keys) {
    $target = Join-Path $destination $artifacts[$source]
    $remote = $metadata.siblings | Where-Object rfilename -eq $source | Select-Object -First 1
    if (-not (Test-Path -LiteralPath $target)) {
        Write-Output "Downloading $source (pinned revision $revision)"
        Invoke-WebRequest "https://huggingface.co/$repository/resolve/$revision/$source" -OutFile "$target.partial" -TimeoutSec 300
        Move-Item -LiteralPath "$target.partial" -Destination $target
    }
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($remote.lfs.sha256 -and $remote.lfs.sha256 -ne $hash) { throw "SHA256 verification failed for $source" }
    $manifest += [pscustomobject]@{ source=$source; file=$artifacts[$source]; sha256=$hash; bytes=(Get-Item -LiteralPath $target).Length }
}
@{ repository=$repository; revision=$revision; license='Apache-2.0'; artifacts=$manifest } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
Write-Output 'Embedding assets are ready. Enable the semantic profile to use them.'
