<#
.SYNOPSIS
    Builds a native executable of the Maven Cleaner CLI using GraalVM native-image.

.DESCRIPTION
    Requires GraalVM JDK 21+ with native-image installed.
    Install via: gu install native-image
    Or use SDKMAN: sdk install java 21.0.2-graal

.EXAMPLE
    .\build-native.ps1
#>

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Check for native-image
$nativeImage = Get-Command native-image -ErrorAction SilentlyContinue
if (-not $nativeImage) {
    Write-Host "native-image not found. Install GraalVM and run: gu install native-image" -ForegroundColor Red
    exit 1
}

Write-Host "GraalVM native-image found: $($nativeImage.Source)" -ForegroundColor Green
Write-Host ""
Write-Host "Building native image (this takes a few minutes)..." -ForegroundColor Cyan

mvn package -pl core,cli -Pnative -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed." -ForegroundColor Red
    exit 1
}

$executable = "cli\target\maven-cleaner.exe"
if (-not (Test-Path $executable)) {
    $executable = "cli\target\maven-cleaner"
}

if (Test-Path $executable) {
    $size = (Get-Item $executable).Length / 1MB
    Write-Host ""
    Write-Host "Native image built successfully!" -ForegroundColor Green
    Write-Host "  Location: $executable" -ForegroundColor White
    Write-Host "  Size: $([math]::Round($size, 1)) MB" -ForegroundColor White
    Write-Host ""
    Write-Host "Run it:" -ForegroundColor Cyan
    Write-Host "  .\$executable --dry-run --skip-upstream"
} else {
    Write-Host "Build completed but executable not found at expected location." -ForegroundColor Yellow
}
