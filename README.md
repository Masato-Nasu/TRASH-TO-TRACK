# TRASH TO TRACK v0.6.0

**Delete data. Refine what remains into music.**

![TRASH TO TRACK v0.6.0](docs/screenshot.jpg)

TRASH TO TRACK is an Android file-cleanup experience where actually deleted local data becomes music credit.

## Core Rule

* Delete real local files from inside **TRASH TO TRACK**.
* Successfully deleted bytes enter **TRASH BANK**.
* **25 MB = 1 TRACK**.
* TRACK generation uses the ESSENCE associated with the remaining credit.
* A TRACK consumes its 25 MB credit **only after the MP3 has been generated and saved successfully**.

## v0.6.0 Reset / Review

This version was reviewed against the accumulated v0.2–v0.5 code and removes old overlapping file-picker / move paths.

### Important Fixes

* **ESSENCE is now consumed with TRACK credit.** Older builds reduced credit but kept old ESSENCE forever; that could make previously deleted material keep influencing future music. v0.6.0 reconciles legacy BANK data on first run and then consumes the exact 25 MB ESSENCE slice after each successful TRACK.
* **Deletion is journaled before the source file is touched.** If the process stops after deletion but before BANK commit, the pending deletion is recovered automatically on the next launch, so a deleted file cannot silently lose its TRACK credit.
* Failed, cancelled, blocked, unsaved, or interrupted generation consumes **neither credit nor ESSENCE**.
* Old retained ESSENCE is reconciled to the amount of credit that actually remains.
* File moving is a single in-app filesystem flow. No Android document-tree picker is used for MOVE.
* Saving TRACKS uses the in-app folder chooser.
* Android Back / edge-back navigates one folder upward. At STORAGE root it does not close the app.
* Normal folder navigation clears ordinary delete selections so hidden selections cannot remain armed in another folder.
* MOVE mode is the only exception: selected move sources are deliberately held while you browse to the destination.
* Default browsing is safe: tap opens a folder / previews a file; **long-press starts selection**.
* No SELECT ALL deletion shortcut.
* Delete uses a readable two-stage review and flags files modified within the last 7 days as RECENT.
* Empty folders have a separate delete action and never create TRACK credit.
* Standard top-level media/system folders (Music, Pictures, Download, DCIM, Android, etc.) are protected from being moved/deleted as whole folders; open them and operate on their contents instead.
* Delete/review/settings/save-folder dialogs use a dedicated high-contrast light theme so text remains readable.
* Background generation continues with a foreground service + partial wake lock and self-clears stale generation state.
* Network/DNS retries remain enabled.

## File Manager Flow

### Browse

* Tap folder: open it.
* Tap file: safe preview / metadata only.
* Android edge-back / Back: go up one folder.
* `‹`: go up one folder.
* `+`: create a folder in the current folder.

### Select Trash

* Long-press a file to begin selection.
* Tap additional items to add/remove them.
* `DELETE → TRACK CREDIT` reviews only deletable files.
* You get credit only for bytes that were actually deleted.

### Move

* Long-press and select one or more files/folders.
* Tap `MOVE`.
* Browse inside TRASH TO TRACK to the destination.
* Tap `MOVE HERE`.
* MOVE never adds TRACK credit.

### Generate

* When **TRASH BANK** contains at least 25 MB, tap `MAKE TRACKS`.
* Choose genre (including TRIP HOP), quantity (1 / 3 / ALL when available), and an in-app save folder.
* Default quantity is **1 TRACK**.
* Tracks save directly into the selected folder; no extra child folder is created automatically.

## Privacy

**PRIVATE MODE** is the default.

* No source image bytes or document wording are sent in PRIVATE MODE.
* Local abstract measurements / metadata are used as musical cues.
* Gemini/Lyria requests explicitly set `store:false`.
* Even in RICH ESSENCE MODE, filenames and folder paths are intentionally omitted from the music prompt.
* Gemini API Key is BYOK and stored with Android Keystore-backed encryption.
* Optional RICH ESSENCE MODE can be explicitly enabled in Settings.

## Permissions

Android 11+ requires **All files access** for this app to function as an in-app file manager.

The Android Settings permission page is the only OS screen required for file access. After permission is granted, browsing, selection, moving, folder creation, deletion, and save-folder choice stay inside TRASH TO TRACK.

## Build / Install on Windows

Open PowerShell in this project folder:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\INSTALL_DEBUG.ps1
```

The build script auto-creates `local.properties` when the Android SDK exists at the standard `%LOCALAPPDATA%\Android\Sdk` path.

`INSTALL_DEBUG.ps1` always rebuilds first, so it cannot accidentally install a stale APK from an older source state.

Java compilation is forced to UTF-8 for consistent Windows builds.

## Safety

**This app permanently deletes files.**

Test with disposable files first. Always use the delete review screen before confirming.
