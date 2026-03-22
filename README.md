# Maven Repository Cleaner (Kotlin)

A modern, fast, and feature-rich clone of the M2 Repo Cleaner, rewritten in Kotlin 2 with JDK 21 support. This tool helps you manage your local Maven repository by identifying and removing old artifact versions, snapshots, and other temporary files like Gradle daemon logs.

## Features

-   **Multi-Module Architecture**:
    -   `core`: High-performance scanning and cleaning logic using Kotlin Coroutines.
    -   `cli`: Command-line interface for headless operations.
    -   `swing-ui`: Modern desktop interface with a responsive UI.
-   **Concurrent Scanning**: Utilizes asynchronous I/O and coroutines for extremely fast repository analysis.
-   **Intelligent Selection**:
    -   **Alphabetical Sorting**: Automatically sorts artifacts by GroupId, ArtifactId, and Version for easier navigation.
    -   **Latest Version Highlighting**: Rows containing the latest version of an artifact are highlighted with a distinct background color.
    -   **Select Snapshots**: Quick batch selection of all snapshot versions.
    -   **Select Old Versions**: Automatically identifies and selects all versions except the latest for each artifact.
    -   **Exclude Snapshots**: Deselects all snapshot versions from the current selection.
-   **Upstream Verification**: Checks artifacts against Maven Central to identify "Local Only" dependencies.
-   **Local-Only Protection**: A core safety feature that ensures any dependency not found in upstream Maven repositories is NEVER deleted, protecting your custom or internal builds. Use the "Protect Local Only" button in the UI or let the tool handle it automatically during deletion.
-   **Dry Run**: Dedicated button to simulate deletions, showing exactly what would be removed and how much space would be freed without modifying any files.
-   **Move to Trash**: Optional support for moving files to the system recycle bin/trash instead of permanent deletion (platform-dependent).
-   **Repository Integrity**: Automatically updates or removes `maven-metadata.xml` files after deletion to keep your local repository consistent with disk contents.
-   **Filtering & Review**: "Show Selected Only" mode allows for easy review of items marked for deletion.
-   **Modern Look**: Swing UI enhanced with **FlatLaf** and a **responsive JToolBar** for a native and clean appearance.
-   **Comprehensive Stats**: Displays total repository size, total size of selected items, and remaining space.

## Prerequisites

-   **JDK 21** or higher.
-   **Maven 3.9+**.

## Getting Started

### Build the Project

Run the following command in the root directory:

```bash
mvn clean install
```

### Run the GUI

```bash
mvn exec:java -Dexec.mainClass="com.maven.cleaner.ui.MainWindowKt" -pl swing-ui
```

### Run the CLI

```bash
mvn exec:java -Dexec.mainClass="com.maven.cleaner.cli.MainKt" -pl cli --args="--help"
```

## Project Structure

-   `core/`: Contains the domain models and scanning/cleaning logic.
-   `cli/`: Command-line implementation.
-   `swing-ui/`: Swing-based desktop application.

## License

This project is licensed under the MIT License - see the LICENSE file for details (if applicable).
