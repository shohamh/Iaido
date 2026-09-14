param(
    [string]$OutputDirectory = "dist",
    [string]$PrivateKeyPath = "secrets/core-engine-update-ed25519-private.pem",
    [Parameter(Mandatory = $true)][string]$DownloadUrl,
    [string]$MinAppVersion = "0.1.1"
)
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutputDirectory))
}
$resolvedKey = if ([IO.Path]::IsPathRooted($PrivateKeyPath)) {
    [IO.Path]::GetFullPath($PrivateKeyPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $repoRoot $PrivateKeyPath))
}
$manifestPath = Join-Path $resolvedOutput "core-engine-manifest.json"
if (-not (Test-Path -LiteralPath $resolvedKey -PathType Leaf)) { throw "Signing key not found: $resolvedKey" }
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Manifest not found: $manifestPath" }

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach ($required in @("artifact", "version", "sha256")) {
    if ([string]::IsNullOrWhiteSpace([string]$manifest.$required)) { throw "Manifest field is missing: $required" }
}
$manifest.minAppVersion = $MinAppVersion
$manifest.downloadUrl = $DownloadUrl
$manifest.signature = ""
$canonical = "artifact=$($manifest.artifact)`ndownloadUrl=$($manifest.downloadUrl)`nminAppVersion=$($manifest.minAppVersion)`nsha256=$($manifest.sha256.ToLowerInvariant())`nversion=$($manifest.version)`n"

$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("ninjakeys-sign-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
$canonicalPath = Join-Path $temporaryDirectory "canonical.txt"
$signaturePath = Join-Path $temporaryDirectory "signature.txt"
try {
    [IO.File]::WriteAllText($canonicalPath, $canonical, (New-Object Text.UTF8Encoding($false)))
    $env:NINJAKEYS_SIGN_KEY = $resolvedKey
    $env:NINJAKEYS_SIGN_INPUT = $canonicalPath
    $env:NINJAKEYS_SIGN_OUTPUT = $signaturePath
    @'
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
var pem = Files.readString(Path.of(System.getenv("NINJAKEYS_SIGN_KEY")));
var encoded = pem.lines().filter(line -> !line.startsWith("---")).reduce("", String::concat);
var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
var signature = Signature.getInstance("Ed25519");
signature.initSign(key);
signature.update(Files.readAllBytes(Path.of(System.getenv("NINJAKEYS_SIGN_INPUT"))));
Files.writeString(Path.of(System.getenv("NINJAKEYS_SIGN_OUTPUT")), Base64.getEncoder().encodeToString(signature.sign()));
/exit
'@ | jshell -q | Out-Null
    $manifest.signature = (Get-Content -LiteralPath $signaturePath -Raw).Trim()
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "Signed core-engine manifest for version $($manifest.version)"
} finally {
    Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
}
