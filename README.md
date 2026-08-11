# TRASH TO TRACK v0.6.0

**Delete data. Turn the afterimage into music.**

TRASH TO TRACK is an Android app whose main interaction is deliberately simple:

1. Find files you genuinely no longer need.
2. Long-press to select them.
3. Tap **DELETE → TRACK CREDIT**.
4. Review the exact files before permanent deletion.
5. Only successfully deleted bytes enter **TRASH BANK**.
6. Every **25 MB** unlocks one full TRACK.
7. Generate 1 / 3 / ALL available TRACKS with Gemini/Lyria BYOK.

The file manager exists only to support that flow. Moving files does **not** earn TRACK CREDIT.

## v0.6.0 interaction reset

- Normal tap never selects a file.
  - Folder: open it.
  - File: safe preview / metadata only.
- Long-press starts selection mode; further taps add/remove items.
- Selection mode shows only the actions that matter: **MOVE** and **DELETE → TRACK CREDIT**.
- Empty folders can be deleted separately; folders never earn music credit.
- MOVE is contained inside TRASH TO TRACK: **MOVE → browse destination → MOVE HERE**.
- Rightward flick in the file list goes exactly one folder level up.
- Android edge-back / Back uses the same folder-up behavior.
- At STORAGE root, Back/edge-swipe does **not** close the app.
- The gesture listener observes without consuming touch events, so scrolling, tapping, and long-press selection keep working.
- The FULL FILE ACCESS button disappears after access is granted.
- TRASH BANK and TRACK generation are visually primary; file-management controls stay secondary.

## Delete safety

Deletion is intentionally conservative:

- Normal browsing cannot delete or select anything.
- Files must first be deliberately selected with a long-press.
- A high-contrast **REVIEW BEFORE DELETE** screen lists every file, size and location.
- Files changed within the last 7 days are marked **RECENT**.
- A final permanent-delete confirmation follows the review.
- TRACK CREDIT is added only after actual deletion succeeds.
- Failed/cancelled deletions earn no credit.

## Music / TRASH BANK

- **25 MB deleted = 1 TRACK credit**.
- Credits accumulate; generation can happen later.
- Full-length Lyria TRACKS can be generated 1 / 3 / ALL at a time.
- TRACK generation runs in a foreground service and can continue with the screen off.
- Successful MP3 save happens before its 25 MB credit is consumed.
- Stale/frozen generation state self-recovers; active generation can be stopped without consuming unfinished credit.
- DNS/network loss uses bounded retries instead of immediately killing a batch.

## Privacy

- Gemini API key is BYOK and stored with Android Keystore-backed encryption.
- **PRIVATE MODE is ON by default.**
- PRIVATE MODE keeps/sends only abstract ESSENCE metadata, not source photos or document wording.
- Interactions requests explicitly use `store:false`.
- Optional RICH ESSENCE MODE must be explicitly enabled in Settings.

## File access

For the in-app file manager to browse and manage shared storage on Android 11+, TRASH TO TRACK requests Android's one-time **All files access** permission. The Android Settings permission screen is the only unavoidable system handoff. After permission is granted, browsing, multi-selection, moving, folder creation, delete review, and TRACK destination selection are handled inside TRASH TO TRACK.

## Build / install (Windows PowerShell)

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\INSTALL_DEBUG.ps1
```

The script builds `TRASH-TO-TRACK-v0.6.0-debug.apk` and installs it to an ADB-connected device.

## First test after update

Use disposable files first:

1. Open a nested folder.
2. Right-flick / Android Back → confirm exactly one level up and no app exit at STORAGE root.
3. Tap a file → confirm preview only.
4. Long-press three files → confirm all three remain selected.
5. Move them to another folder with **MOVE → MOVE HERE**.
6. Select disposable files → **DELETE → TRACK CREDIT** → review → delete.
7. Confirm TRASH BANK increases only by bytes actually deleted.
8. Generate one TRACK and confirm MP3 output.
