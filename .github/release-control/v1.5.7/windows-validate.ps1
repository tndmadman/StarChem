param(
    [Parameter(Mandatory = $true)][string]$ArtifactDirectory,
    [Parameter(Mandatory = $true)][string]$CommitSha
)

$ErrorActionPreference = 'Stop'
$zip = Join-Path $ArtifactDirectory 'StarChem-v1.5.7.zip'
$checksum = Join-Path $ArtifactDirectory 'StarChem-v1.5.7.zip.sha256'
$expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw 'Release checksum mismatch.' }

$root = Join-Path $env:RUNNER_TEMP 'starchem-v157-loopback-tls'
Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
Expand-Archive -LiteralPath $zip -DestinationPath $root
Push-Location (Join-Path $root 'StarChem')
try {
    $version = (& cmd /c run-starchem.bat --version | Out-String).Trim()
    $expectedVersion = "StarChem 1.5.7 ($($CommitSha.Substring(0, 12)))"
    if ($version -ne $expectedVersion) { throw "Unexpected client version: $version" }

    $help = (& cmd /c run-starchem-server.bat --help | Out-String)
    if ($LASTEXITCODE -ne 0 -or $help -notmatch '--server \[PORT\]') {
        throw 'Windows server launcher smoke test failed.'
    }
} finally {
    Pop-Location
}
