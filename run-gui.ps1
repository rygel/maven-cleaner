<#
.SYNOPSIS
    Starts the Maven Repository Cleaner GUI (Swing).

.PARAMETER SkipBuild
    Skips the Maven build step.

.EXAMPLE
    .\run-gui.ps1
    .\run-gui.ps1 -SkipBuild
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $SkipBuild) {
    Write-Host "Building project..." -ForegroundColor Cyan
    mvn install -q -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed." -ForegroundColor Red
        exit 1
    }
}

Write-Host "Starting GUI..." -ForegroundColor Green
mvn -pl swing-ui exec:java "-Dexec.mainClass=com.maven.cleaner.ui.MainWindowKt"
