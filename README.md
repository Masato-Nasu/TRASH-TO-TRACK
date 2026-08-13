# TRASH TO TRACK v0.6.0

**Delete data. Refine what remains into music.**

![TRASH TO TRACK v0.6.0](docs/screenshot.jpg)

TRASH TO TRACK is an Android file-cleanup experience where actually deleted local data becomes music credit.

## Core rule

- Delete real local files from inside TRASH TO TRACK.
- Successfully deleted bytes enter **TRASH BANK**.
- **25 MB = 1 TRACK**.
- TRACK generation uses the ESSENCE associated with the remaining credit.
- A TRACK consumes its 25 MB credit **only after the MP3 has been generated and saved successfully**.

## v0.6.0 reset / review

This version was reviewed against the accumulated 0.2–0.5 code and removes old overlapping file-picker / move paths.

### Important fixes

- **ESSENCE is now consumed with TRACK credit.** Older builds reduced credit but kept old ESSENCE forever; that could make previously deleted material keep influencing future music. v0.6.0 reconciles legacy BANK data on first run and then consumes the exact 25 MB ESSENCE slice after each successful TRACK.
- **Deletion is journaled before the source file is touched.** If the process stops after deletion but before BANK commit, the pending deletion is recovered automatically on the next launch, so a deleted file cannot silently lose its TRACK credit.
- Failed, cancelled, blocked, unsaved, or interrupted generation consumes **neither credit nor ESSENCE**.
- Old retained ESSENCE is reconciled to the amount of credit that actually remains.
- File moving is a single in-app filesystem flow. No Android document-tree picker is used for MOVE.
- Saving TRACKS uses the in-app folder chooser.
- Android Back / edge-back navigates one folder upward. At STORAGE root it does not close the app.
- Normal folder navigation clears ordinary delete selections so hidden selections cannot remain armed in another folder.
- MOVE mode is the only exception: selected move sources are deliberately held while you browse to the destination.
- Default browsing is safe: tap opens a folder / previews a file; **long-press starts selection**.
- No SELECT ALL deletion shortcut.
- Delete uses a readable two-stage review and flags files modified within the last 7 days as RECENT.
- Empty folders have a separate delete action and never create TRACK credit.
- Standard top-level media/system folders (Music, Pictures, Download, DCIM, Android, etc.) are protected from being moved/deleted as whole folders; open them and operate on their contents instead.
- Delete/review/settings/save-folder dialogs use a dedicated high-contrast light theme so text remains readable.
- Background generation continues with a foreground service + partial wake lock and self-clears stale generation state.
- Network/DNS retries remain enabled.

## File manager flow

### Browse

- Tap folder: open it.
- Tap file: safe preview / metadata only.
- Android edge-back / Back: go up one folder.
- `‹`: go up one folder.
- `+`: create a folder in the current folder.

### Select trash

- Long-press a file to begin selection.
- Tap additional items to add/remove them.
- `DELETE → TRACK CREDIT` reviews only deletable files.
- You get credit only for bytes that were actually deleted.

### Move

- Long-press and select one or more files/folders.
- Tap `MOVE`.
- Browse inside TRASH TO TRACK to the destination.
- Tap `MOVE HERE`.
- MOVE never adds TRACK credit.

### Generate

- When TRASH BANK contains at least 25 MB, tap `MAKE TRACKS`.
- Choose genre (including TRIP HOP), quantity (1 / 3 / ALL when available), and an in-app save folder.
- Default quantity is **1 TRACK**.
- Tracks save directly into the selected folder; no extra child folder is created automatically.

## Privacy

PRIVATE MODE is the default.

- No source image bytes or document wording are sent in PRIVATE MODE.
- Local abstract measurements / metadata are used as musical cues.
- Gemini/Lyria requests explicitly set `store:false`.
- Even in RICH ESSENCE MODE, filenames and folder paths are intentionally omitted from the music prompt.
- Gemini API Key is BYOK and stored with Android Keystore-backed encryption.
- Optional RICH ESSENCE MODE can be explicitly enabled in Settings.

## Permissions

Android 11+ requires **All files access** for this app to function as an in-app file manager. The Android Settings permission page is the only OS screen required for file access; after permission is granted, browsing, selection, moving, folder creation, deletion, and save-folder choice stay inside TRASH TO TRACK.

## Build / install on Windows

Open PowerShell in this project folder:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\INSTALL_DEBUG.ps1
```

The build script auto-creates `local.properties` when the Android SDK exists at the standard `%LOCALAPPDATA%\Android\Sdk` path. `INSTALL_DEBUG.ps1` always rebuilds first, so it cannot accidentally install a stale APK from an older source state. Java compilation is forced to UTF-8 for consistent Windows builds.

## Safety

This app permanently deletes files. Test with disposable files first. Always use the delete review screen before confirming.
DATA BOTTLE — Android Setup & Permissions

DATA BOTTLE visualizes information from your Android device as liquid inside virtual bottles.

Most functions work automatically. However, some features require additional Android permissions or settings.

The permissions required depend on which DATA BOTTLE features you choose to use.

1. Display over other apps — Required for Floating Bottle

Required only if you want DATA BOTTLE to remain visible on top of other apps.

The Floating Bottle feature allows DATA BOTTLE to stay on screen while you are using Chrome, LINE, YouTube, Maps, or other applications.

Android treats this as a special permission because the app is drawing a window above other applications.

To enable it:

Android Settings → Apps → Special app access → Display over other apps → DATA BOTTLE → Allow display over other apps

The exact wording may vary depending on your Android device.

DATA BOTTLE uses this permission only to display the bottle interface above other apps. It does not allow DATA BOTTLE to read the contents of the apps underneath it.

Technically, Android uses the SYSTEM_ALERT_WINDOW permission and TYPE_APPLICATION_OVERLAY windows for this feature. Users must explicitly grant this special permission through Android Settings.

If you do not enable this permission

DATA BOTTLE will still work normally when the app itself is open.

Only the Floating Bottle feature will be unavailable.

2. Keep Floating Bottle Running

If you want the Floating Bottle to remain visible even after leaving the DATA BOTTLE app, Android may run DATA BOTTLE as a Foreground Service.

A foreground service is designed for a task that remains noticeable to the user while the app is not currently open. Android requires foreground services to provide an ongoing notification.

You may therefore see a notification such as:

DATA BOTTLE is running

or:

Floating Bottle is active

This is normal Android behavior and helps make it clear that DATA BOTTLE is still running.

There is no separate user-facing “Foreground Service permission” switch that you need to enable.

On recent Android versions, foreground services must also be classified by their intended use. Android 14 and later require an appropriate foreground service type, and applications whose use does not fit another category can use the specialUse type when appropriate.

For Android 15 and later, background launching rules are stricter. When an application relies on its overlay permission to start a foreground service from the background, the overlay must already be visible before the service is started.

3. Notifications — Recommended for Floating Mode

On Android 13 and later, Android may ask:

Allow DATA BOTTLE to send you notifications?

For DATA BOTTLE, this permission is mainly useful for showing the status of the Floating Bottle service.

Recommended setting:

Allow

This makes it easy to see that the Floating Bottle is active and gives DATA BOTTLE a clear place to provide a Stop action.

Android does not require the notification permission itself in order to start a foreground service. However, the service must still create a notification. If notification permission is denied, the foreground-service notice may not appear in the normal notification drawer, although Android still exposes the running service through its system Task Manager.

4. Mobile Data — Usage Access

Required for the MOBILE DATA bottle.

Android normally prevents ordinary apps from reading device-wide network-usage statistics.

To calculate how much mobile data you have used during your billing period, DATA BOTTLE needs Android's Usage Access permission.

To enable it:

Android Settings → Apps → Special app access → Usage access → DATA BOTTLE → Allow usage access

The exact menu name may vary depending on your device.

DATA BOTTLE uses Android's NetworkStatsManager to obtain network-usage statistics. Device-wide network summaries require PACKAGE_USAGE_STATS, which the user grants through Android Settings.

What this permission is used for

It allows DATA BOTTLE to calculate values such as:

Mobile Data Used: 12.4 GB

It does not give DATA BOTTLE access to your:

Messages

Photos

Contacts

Passwords

Browsing content

Microphone

Camera

If Usage Access is disabled, DATA BOTTLE can still be used, but the MOBILE DATA bottle cannot automatically display device-wide cellular usage.

5. Monthly Mobile Data Limit

Android can provide the amount of mobile data used, but DATA BOTTLE also needs to know the size of your mobile plan.

Enter your monthly data allowance manually.

Example:

Monthly Data Limit: 20 GB

DATA BOTTLE treats this amount as one full bottle.

If you have used:

5 GB / 20 GB

the bottle is:

25% full

If you have used:

10 GB / 20 GB

the bottle is:

50% full

If you have used:

20 GB / 20 GB

the bottle is:

100% full

6. Data Used Beyond Your Monthly Limit

DATA BOTTLE can continue visualizing usage even after the normal bottle reaches 100%.

For example:

Monthly Limi

