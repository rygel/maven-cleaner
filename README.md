# Maven Cleaner

A tool to clean up your local Maven repository and Gradle caches. Scans for old artifact versions, checks Maven Central for upstream availability, protects local-only builds, and reclaims disk space safely.

Built with Kotlin, JDK 21, and FlatLaf.

## Features

### Maven Repository Cleaning
- **Scan all versions** or **snapshots only** across your entire `~/.m2/repository`
- **Select Old Versions** — automatically identifies all versions except the latest per artifact
- **Upstream Verification** — checks each artifact against Maven Central
- **Local-Only Protection** — artifacts not found upstream are never deleted
- **Metadata Refresh** — updates `maven-metadata.xml` after deletion to keep the repository consistent
- **Latest Version Safety** — warns before deleting all versions of an artifact

### Gradle Cache Cleaning
- **Daemon logs** — old `.out.log` files (>7 days)
- **Caches** — version caches, transform caches, build caches, module caches
- **Distributions** — downloaded Gradle wrapper ZIPs
- **Build scan data** and **native platform files**

### Split Repository Migration
Supports Maven 3.9+ split local repository layout. Migrates an existing flat repository into:
- `~/.m2/repository/cached/` — downloaded dependencies (safe to delete anytime)
- `~/.m2/repository/installed/` — locally built artifacts (protected)

Detects current layout status: Classic, Partially Split, or Fully Split.

### Safety
- **Path validation** — refuses to operate on paths outside allowed roots
- **Symlink protection** — rejects symlinks, validates every walked entry stays within bounds
- **TOCTOU mitigation** — re-validates paths immediately before each destructive operation
- **Trash verification** — confirms files are actually gone after Recycle Bin operations
- **Bulk trash via JNA** — uses Windows `SHFileOperation` for efficient batch Recycle Bin moves
- **XXE protection** — disabled on all XML parsers and transformers
- **Cancel support** — all long-running operations can be cancelled mid-progress

### UI
- Grouped side panel with Scan, Selection, Upstream, Actions, Options, and Layout sections
- Progress dialogs for all long-running operations (scans, upstream checks, deletion, migration)
- Numeric size sorting (not alphabetical)
- Latest version highlighting (green rows)
- Dry Run mode
- Move to Trash (on by default)
- Help > About dialog

## Prerequisites

- **JDK 21** or higher
- **Maven 3.9+**

## Quick Start

### PowerShell

```powershell
# GUI
.\run-gui.ps1

# CLI — cleanup
.\run-cli.ps1 -DryRun
.\run-cli.ps1 -SkipUpstream

# CLI — migrate to split layout
.\run-cli.ps1 -MigrateSplit -DryRun
.\run-cli.ps1 -MigrateSplit
```

### Maven

```bash
# Build
mvn install -DskipTests

# GUI
mvn -pl swing-ui exec:java -Dexec.mainClass=com.maven.cleaner.ui.MainWindowKt

# CLI
mvn -pl cli exec:java -Dexec.mainClass=com.maven.cleaner.cli.MainKt -Dexec.args="--dry-run --skip-upstream"
mvn -pl cli exec:java -Dexec.mainClass=com.maven.cleaner.cli.MainKt -Dexec.args="--migrate-split --dry-run"
```

### CLI Flags

| Flag | Description |
|---|---|
| `--dry-run` | Simulate without deleting/moving |
| `--skip-upstream` | Skip Maven Central checks |
| `--repo <path>` | Custom repository path |
| `--migrate-split` | Migrate to split repository layout |

## Project Structure

```
maven-cleaner/
  core/          Domain models, scanning, cleaning, migration logic
  cli/           Command-line interface
  swing-ui/      Swing desktop application (FlatLaf)
```

## Build Configuration

The project enforces:
- **Java 21** and **Maven 3.9+** via maven-enforcer-plugin
- **Dependency convergence** and **explicit plugin versions**
- **Surefire timeout** — 300s forked process, 120s per JUnit test
- **Detekt** — Kotlin linter on verify phase
- **JaCoCo** — code coverage
- **Split local repository** — enabled in `.mvn/maven.config`
- **4 threads** — parallel module builds

Swing tests are skipped by default. Use `-Ptest-desktop` to run them in a container.

## License

Licensed under the [Apache License 2.0](LICENSE).
