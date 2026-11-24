param(
    [Parameter(Mandatory)][string]$MlKitSrt,
    [switch]$Pro
)

$ErrorActionPreference = 'Stop'

function Read-Subtitles([string]$Path) {
    $content = Get-Content -LiteralPath $Path -Encoding UTF8 -Raw
    foreach ($block in ($content.Trim() -split '\r?\n[ \t]*\r?\n')) {
        $lines = $block -split '\r?\n', 3
        if ($lines.Count -ne 3 -or $lines[0] -notmatch '^\d+$' -or
                $lines[1] -notmatch '^\d{2}:\d{2}:\d{2},\d{3} --> \d{2}:\d{2}:\d{2},\d{3}$' -or
                [string]::IsNullOrWhiteSpace($lines[2])) {
            throw "Invalid SRT segment in $Path."
        }
        [pscustomobject]@{ Time = $lines[1]; Text = $lines[2].Trim() }
    }
}

$fixture = Join-Path (Split-Path -Parent $PSScriptRoot) 'samples/translation/ja-vi-review.srt'
$source = @(Read-Subtitles $fixture)
$mlkit = @(Read-Subtitles $MlKitSrt)
if ($source.Count -ne 8 -or $mlkit.Count -ne $source.Count) {
    throw 'The ML Kit export must contain all eight translated segments.'
}
for ($i = 0; $i -lt $source.Count; $i++) {
    if ($source[$i].Time -ne $mlkit[$i].Time -or $source[$i].Text -eq $mlkit[$i].Text) {
        throw "ML Kit segment $($i + 1) does not match the fixture or still contains the source text."
    }
}

if ([string]::IsNullOrWhiteSpace($env:DEEPL_AUTH_KEY)) {
    throw 'Set DEEPL_AUTH_KEY in this PowerShell session.'
}
$server = if ($Pro) { 'api.deepl.com' } else { 'api-free.deepl.com' }
$payload = @{ text = @($source.Text); source_lang = 'JA'; target_lang = 'VI' } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "https://$server/v2/translate" -TimeoutSec 45 `
    -Headers @{ Authorization = "DeepL-Auth-Key $env:DEEPL_AUTH_KEY" } `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload))
$deepL = @($result.translations)
if ($deepL.Count -ne $source.Count) {
    throw 'DeepL returned a different number of translations.'
}
for ($i = 0; $i -lt $source.Count; $i++) {
    if ([string]::IsNullOrWhiteSpace($deepL[$i].text)) {
        throw "DeepL returned an empty translation for segment $($i + 1)."
    }
    [pscustomobject]@{
        Segment = $i + 1
        Timestamp = $source[$i].Time
        Japanese = $source[$i].Text
        MLKit = $mlkit[$i].Text
        DeepL = $deepL[$i].text
    }
}
