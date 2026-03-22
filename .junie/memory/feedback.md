[2026-03-22 14:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "size discrepancy",
    "EXPECTATION": "User expects the app’s reported size to match or be clearly comparable to the OS-reported total size of the .m2 folder.",
    "NEW INSTRUCTION": "WHEN displaying scan results THEN show both total repo size and selected-for-deletion size with clear labels and units"
}

[2026-03-22 14:39] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "selection logic",
    "EXPECTATION": "Selecting old versions should only select non-latest per artifact and leave the latest unselected; also provide a button to exclude snapshots.",
    "NEW INSTRUCTION": "WHEN user clicks \"Select Old Versions\" THEN select all non-latest versions and keep latest unselected. WHEN user clicks \"Exclude Snapshots\" THEN remove snapshot versions from view and current selection"
}

[2026-03-22 14:46] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "UI control regression",
    "EXPECTATION": "User expects a dedicated Dry Run button to be present instead of a checkbox.",
    "NEW INSTRUCTION": "WHEN implementing dry-run in the Swing UI THEN use a dedicated 'Dry Run' button that initiates simulation"
}

[2026-03-22 14:52] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "local-only protection",
    "EXPECTATION": "All artifacts that are local-only (not found in upstream) must never be deleted.",
    "NEW INSTRUCTION": "WHEN artifact not found in upstream THEN mark protected and disallow selection, dry-run, and deletion operations"
}

[2026-03-22 14:56] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "deletion progress UI",
    "EXPECTATION": "User wants a visible progress dialog during deletion that shows what is happening.",
    "NEW INSTRUCTION": "WHEN performing deletion THEN show a modal progress dialog with live status and progress bar"
}

[2026-03-22 15:08] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "trash fallback",
    "EXPECTATION": "If moving to trash fails, nothing should be deleted permanently; abort and inform the user.",
    "NEW INSTRUCTION": "WHEN move-to-trash fails THEN cancel deletion and show an error dialog; keep files intact"
}

[2026-03-22 15:11] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "trash fallback",
    "EXPECTATION": "If moving to trash fails, deletion must be aborted and nothing deleted permanently; inform the user.",
    "NEW INSTRUCTION": "WHEN move-to-trash fails for any file THEN cancel deletion, keep files, and show an error dialog"
}

[2026-03-22 15:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "trash fallback",
    "EXPECTATION": "Deletion must not proceed permanently if move-to-trash fails; abort and inform the user.",
    "NEW INSTRUCTION": "WHEN move-to-trash fails for any file THEN cancel deletion, keep files, and show an error dialog"
}

[2026-03-22 15:13] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "trash fallback",
    "EXPECTATION": "If moving to trash fails, deletion must not proceed permanently; abort and inform the user.",
    "NEW INSTRUCTION": "WHEN move-to-trash fails for any file THEN cancel deletion, keep files intact, and show an error dialog"
}

[2026-03-22 15:15] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "logging backend",
    "EXPECTATION": "User wants SLF4J to use Logback as the logging implementation instead of slf4j-simple.",
    "NEW INSTRUCTION": "WHEN setting logging implementation THEN use Logback Classic with a logback.xml configuration file"
}

[2026-03-22 15:22] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "post-deletion refresh",
    "EXPECTATION": "After deleting items, the UI should remove only those entries and keep the remaining list intact, not clear the whole table.",
    "NEW INSTRUCTION": "WHEN deletion completes successfully THEN remove deleted rows from the table without clearing other entries"
}

