<#
.SYNOPSIS
    Starts the Maven Repository Cleaner CLI.

.PARAMETER DryRun
    Simulates deletion without actually removing files.

.PARAMETER SkipUpstream
    Skips the Maven Central upstream check.

.PARAMETER Repo
    Path to the Maven repository. Defaults to ~/.m2/repository.

.PARAMETER MigrateSplit
    Migrate repository to the split layout (cached/ + installed/).

.PARAMETER SkipBuild
    Skips the Maven build step.

.EXAMPLE
    .\run-cli.ps1
    .\run-cli.ps1 -DryRun
    .\run-cli.ps1 -SkipUpstream -DryRun
    .\run-cli.ps1 -Repo "D:\maven-repo"
    .\run-cli.ps1 -MigrateSplit
    .\run-cli.ps1 -MigrateSplit -DryRun
#>
param(
    [switch]$DryRun,
    [switch]$SkipUpstream,
    [switch]$MigrateSplit,
    [string]$Repo,
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

# Build args
$appArgs = @()
if ($MigrateSplit) { $appArgs += "--migrate-split" }
if ($DryRun) { $appArgs += "--dry-run" }
if ($SkipUpstream) { $appArgs += "--skip-upstream" }
if ($Repo) { $appArgs += "--repo", $Repo }

$execArgs = $appArgs -join ' '

Write-Host "Starting CLI..." -ForegroundColor Green
if ($execArgs) {
    mvn -pl cli exec:java "-Dexec.mainClass=com.maven.cleaner.cli.MainKt" "-Dexec.args=$execArgs"
} else {
    mvn -pl cli exec:java "-Dexec.mainClass=com.maven.cleaner.cli.MainKt"
}
