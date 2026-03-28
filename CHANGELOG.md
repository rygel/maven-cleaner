# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [0.6.0] - 2026-03-28

### Added
- **Gradle Cache Cleaning** — scan and delete daemon logs, version caches, transform caches, build caches, module caches, wrapper distributions, build scan data, and native files
- **Split Repository Migration** — migrate flat `~/.m2/repository` to Maven 3.9+ split layout (`cached/` + `installed/`) by checking each artifact against Maven Central
- **Layout Detection** — detects Classic, Partially Split, or Fully Split repository status with color-coded label in the GUI
- **JNA Bulk Trash** — uses `W32FileUtils.moveToTrash()` on Windows for efficient batch Recycle Bin operations instead of per-file calls
- **Cancel Support** — all long-running operations (scan, upstream check, deletion, migration) can be cancelled via progress dialog
- **Progress Dialogs** — added to all long-running operations: Maven scan, Gradle scan, upstream check, deletion, and migration
- **Numeric Size Sorting** — Size column stores raw bytes and sorts numerically, displays formatted string
- **Path Validation** — `ArtifactCleaner` validates all paths against `allowedRoots` before any destructive operation
- **Symlink Protection** — rejects symlinks at top level, validates every walked entry during directory deletion
- **TOCTOU Mitigation** — re-validates paths immediately before each destructive operation
- **Trash Verification** — confirms files are gone after Recycle Bin operation on Windows
- **Latest Version Guard** — GUI warns before deleting all versions of an artifact
- **XXE Protection** — disabled external entities on `DocumentBuilderFactory` and `TransformerFactory`
- **About Dialog** — Help > About Maven Cleaner with feature list and license info
- **Menu Bar** — Help menu with About item
- **Side Panel Layout** — grouped buttons: Scan Maven, Scan Gradle, Selection, Upstream, Actions, Options, Repository Layout
- **Select All / Select None** buttons
- **`--migrate-split` CLI flag** — run repository migration from the command line
- **`--repo` CLI flag** — custom repository path
- **PowerShell Scripts** — `run-cli.ps1` and `run-gui.ps1` with parameters for all CLI flags
- **Apache License 2.0**
- **Dependabot** — weekly Maven and GitHub Actions updates, Kotlin ignored
- **Detekt** — Kotlin linter with formatting rules on verify phase
- **JaCoCo** — code coverage reporting
- **Maven Enforcer** — Java 21, Maven 3.9+, dependency convergence, explicit plugin versions
- **Maven Versions Plugin** — `versions:display-dependency-updates` / `versions:display-plugin-updates`
- **Split Local Repository** — enabled in `.mvn/maven.config`
- **Surefire Timeout** — 300s forked process, 120s per JUnit test via `junit-platform.properties`
- **Swing Test Isolation** — tests skipped by default, `-Ptest-desktop` profile for containerized execution
- **98 Tests** — `VersionStringComparatorTest`, `FormatUtilsTest`, `RepositoryScannerTest`, `ArtifactCleanerTest`, `MetadataRefresherTest`, `RepositoryMigratorTest`, `EndToEndTest`, `DeletionTest`

### Changed
- **`DesktopTrashProvider` moved out of core** — `core` no longer depends on `java.awt.Desktop`; provider lives in `swing-ui`
- **`UpstreamChecker` returns `UpstreamStatus` enum** — `AVAILABLE`, `LOCAL_ONLY`, `UNKNOWN` instead of `Boolean`; rate-limit (429) and errors return `UNKNOWN` instead of being misclassified
- **`UpstreamChecker` is `AutoCloseable`** — `HttpClient` properly closed
- **`findOldSnapshots` preserves the latest version** — no longer deletes the only remaining version of an artifact
- **Version comparators consolidated** — single `VersionStringComparator` shared by `VersionComparator` and `MetadataRefresher`
- **`formatSize` extracted to core** — removed duplicates from CLI and swing-ui
- **Upstream JSON parsing** — uses regex to handle whitespace variations in `"numFound": 0`
- **`MetadataRefresher.cleanupArtifactDirectory`** — only deletes metadata files, never the directory itself
- **`ArtifactCleaner.deletePaths` restructured** — phase 1: parallel size calculation, phase 2: bulk trash or parallel deletion, phase 3: metadata refresh
- **`CoroutineScope` and `UpstreamChecker` cleaned up on window close**
- **Removed unused `isLocalOnly` mutable field** from `ArtifactVersion` data class
- **Removed unused imports** — `ConcurrentLinkedQueue`, `Executors`

### Fixed
- **`Files.walk()` stream leaks** — added `.use {}` to `calculateSize()` and `deleteDirectory()`
- **`calculateSize` was following symlinks** — removed `FOLLOW_LINKS` option
- **CLI duplicate versions** — `findOldVersions` + `findOldSnapshots` overlap deduplicated by path
- **CLI upstream checks sequential** — parallelized with `Semaphore(10)`
- **GUI `scan(snapshotsOnly)` parameter ignored** — now filters correctly
- **GUI `updateSelectedSize()` filesystem I/O on EDT** — uses cached sizes
- **GUI `checkUpstream()` blocking EDT** — HTTP calls moved to `Dispatchers.IO`
- **CLI `--repo` bypassed `allowedRoots`** — custom path now added to allowed roots
- **`exec-maven-plugin` wrong artifactId** — was `maven-exec-plugin`, corrected

## [1.0.0] - 2026-03-22

### Added
- Initial release of the Maven Repository Cleaner
- Core scanning engine with Kotlin coroutines
- Multi-module project: `core`, `cli`, `swing-ui`
- Swing GUI with FlatLaf
- Select Snapshots, Select Old Versions, Exclude Snapshots
- Upstream verification against Maven Central
- Local-Only protection
- Dry Run mode
- Move to Trash support
- Metadata cleanup after deletion
- Progress tracking dialog
