param(
    [Parameter(Mandatory)]
    [string]$Serial,

    [string]$Resume
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:TEMP 'ai-player-voskvn'
$inputDir = Join-Path $work 'vosk-benchmark'
$corpus = Join-Path $inputDir 'corpus'
$models = @('vosk-model-small-vn-0.4', 'vosk-model-vn-0.4')
$package = 'com.aiplayer.android'
$runner = "$package.test/com.aiplayer.android.VietnameseModelBenchmark"

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE."
    }
}

function Invoke-Device {
    Invoke-Checked 'adb' (@('-s', $Serial) + $args)
}

Invoke-Device get-state
Invoke-Device shell getprop ro.product.model
Invoke-Checked (Join-Path $root 'gradlew.bat') @(
    '-p', $root, ':app:assembleStandaloneDebug', ':app:assembleStandaloneDebugAndroidTest'
)

New-Item -ItemType Directory -Force $corpus | Out-Null
$video = Join-Path $root 'samples/video_landscape.mp4'
Invoke-Checked 'ffmpeg' @(
    '-nostdin', '-loglevel', 'error', '-y', '-i', $video, '-map', '0:a:0',
    '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-f', 's16le',
    (Join-Path $corpus 'video_landscape.pcm')
)

foreach ($model in $models) {
    if (-not (Test-Path (Join-Path $inputDir "$model/am/final.mdl"))) {
        $archive = Join-Path $work "$model.zip"
        Invoke-WebRequest "https://alphacephei.com/vosk/models/$model.zip" -OutFile $archive
        Expand-Archive -LiteralPath $archive -DestinationPath $inputDir -Force
    }
}

Invoke-Device install -r (Join-Path $root 'app/build/outputs/apk/standalone/debug/app-standalone-debug.apk')
Invoke-Device install -r (Join-Path $root 'app/build/outputs/apk/androidTest/standalone/debug/app-standalone-debug-androidTest.apk')
Write-Host "Uploading benchmark files..."
Invoke-Device push $inputDir /data/local/tmp/
Write-Host "Copying models and audio into the app sandbox..."
Invoke-Device shell -n "run-as $package mkdir -p files/vosk-benchmark"
Invoke-Device shell -n "set -o pipefail; tar -C /data/local/tmp/vosk-benchmark -cf - . | run-as $package tar -C files/vosk-benchmark -xf -"
Write-Host "Benchmark files ready."

$completed = @{}
if ($Resume) {
    $results = (Resolve-Path -LiteralPath $Resume).Path
    foreach ($line in Get-Content -LiteralPath $results -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $row = $line | ConvertFrom-Json
        if ($row.model -notin $models -or $row.round -notin (1..3)) {
            throw "Unexpected model or round in $results."
        }
        $key = "$($row.round):$($row.model)"
        if ($completed.ContainsKey($key)) { throw "Duplicate result for $key." }
        $completed[$key] = $true
    }
} else {
    $results = Join-Path $work ("results-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jsonl')
}
foreach ($round in 1..3) {
    $order = if ($round % 2 -eq 0) { @($models[1], $models[0]) } else { $models }
    foreach ($model in $order) {
        if ($completed.ContainsKey("${round}:$model")) { continue }
        Write-Host "Round $round/3 - $model"
        $previousEncoding = [Console]::OutputEncoding
        try {
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
            $output = Invoke-Device shell -n am instrument -w -r -e model $model -e timingOnly true $runner |
                ForEach-Object {
                    Write-Host $_
                    $_
                }
        } finally {
            [Console]::OutputEncoding = $previousEncoding
        }
        $match = @($output | Select-String '^INSTRUMENTATION_RESULT: benchmark=(.*)$')
        if ($match.Count -ne 1) { throw "Benchmark returned no result for $model." }
        $row = $match[0].Matches[0].Groups[1].Value | ConvertFrom-Json
        $row | Add-Member -NotePropertyName round -NotePropertyValue $round
        $row | ConvertTo-Json -Depth 8 -Compress | Add-Content -Encoding utf8 $results
    }
}
Write-Host "Results: $results"
