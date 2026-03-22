# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-03-22

### Added
- Initial release of the Maven Repository Cleaner (Kotlin).
- **Alphabetical Sorting**: Automatically sorts artifacts by GroupId, ArtifactId, and Version for easier navigation.
- **Latest Version Highlighting**: Rows containing the latest version of an artifact are highlighted with a distinct background color.
- **Core Engine**: Fast, concurrent scanning of local Maven repository using Kotlin coroutines.
- **Multi-Module Project**: Separated into `core`, `cli`, and `swing-ui`.
- **GUI**: Developed a modern Swing interface using **FlatLaf**.
- **Batch Selection**: Added "Select Snapshots" and "Select Old Versions" algorithms to identify removable artifacts automatically.
- **Exclude Snapshots**: New button to deselect all snapshot versions from the current selection.
- **Dry Run**: Dedicated button to simulate the deletion process, showing exactly what would be removed and how much space would be freed.
- **UI Improvements**: Switched to `JToolBar` for better component management and increased default window size to prevent UI truncation.
- **Stats**: Real-time calculation of total repository size and total size of selected items.
- **Move to Trash**: Added option to move files to system recycle bin/trash instead of permanent deletion.
- **Metadata Cleanup**: Automatically updates `maven-metadata.xml` files after deleting artifact versions to maintain repository consistency. Enhanced with namespace awareness and robust XML processing.
- **Progress Tracking**: Added a modal progress dialog with a progress bar and status messages to show real-time deletion status.
- **Local-Only Protection**: Reinforced protection for local artifacts; added "Protect Local Only" button to UI and automatic protection in CLI.
- **Kotlin 2 Support**: Fully compatible with Kotlin 2.1.10 and JDK 21.

### Fixed
- **Selection Logic**: Fixed "Select Old Versions" selecting all rows; it now correctly groups artifacts and preserves the latest version of each.
- **Scan Results**: Updated the repository scan to show all versions, allowing for manual review and partial selection.
- **Metadata Consistency**: Fixed potential issues where Maven would encounter stale version information after manual deletion. Now supports XML namespaces in metadata files.
- **Move to Trash**: Fixed "shitty behavior" where files were permanently deleted if "Move to Trash" failed. Now it aborts and informs the user to prevent accidental permanent deletion.

### Performance
- Optimized directory scanning to use multiple threads via `Dispatchers.IO` and asynchronous file traversal.
- Improved responsiveness in the UI by offloading all I/O and network tasks to background coroutines.
- **Parallel Deletion**: Increased concurrency and optimized "Move to Trash" task ordering to significantly speed up large deletion batches while maintaining UI progress reporting.

### Security
- Added protection against deleting artifacts not found on upstream Maven repositories.
