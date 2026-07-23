# Orbit Capture (Android)

Orbit Capture is the phone-side companion app for Orbit Studio's photogrammetry rig. It walks a photographer through a scan session and bundles the shots into a ZIP that matches the pipeline's `bundle.py` contract, ready to hand off to the laptop.

## Build

There is no Gradle wrapper checked in — use the installed Gradle:

```
C:\Users\shave\gradle\gradle-8.11.1\bin\gradle.bat -p C:\Users\shave\orbit-studio\android assembleDebug
```

Output APK: `C:\Users\shave\orbit-studio\android\app\build\outputs\apk\debug\app-debug.apk`

## Install on the phone

1. Copy `app-debug.apk` to the phone over USB or via Google Drive.
2. On the phone, enable "install unknown apps" for the app you used to open the file (Settings > Apps > Special access > Install unknown apps).
3. Open the copied APK file and tap install.

## Uploading 360 photos to Kuula

Home > "Upload to Kuula" opens kuula.co inside the app with the file picker
and login persistence wired up (Kuula has no upload API — their mobile site
is the supported path). Log in once and you stay logged in. Batch upload
works. If you sign in to Kuula with Google, the flow hands off to your
browser (Google blocks sign-in inside embedded views); the "Open in browser"
button does the same on demand. Kuula does not accept .insp files — export
an equirectangular JPG from the Insta360 app first.

This feature added the INTERNET permission in v0.11.0; everything else in
the app still works fully offline.

## Getting the bundle back to the laptop

**Over Wi-Fi (v0.11.2+):** start the laptop server with `python server.py --lan`
(the default bind is localhost-only, so `--lan` is required; allow it through
Windows Firewall when prompted). On the phone's Done screen, enter the laptop's
LAN address (e.g. `192.168.1.23:7360`) and tap Send bundle — the bundle arrives
as a new project in the studio at localhost:7360, crops included. The address
is remembered.

**Manually:** Orbit Capture writes a ZIP bundle (manifest + `crops/` photos,
stored uncompressed). Copy that ZIP off the phone the same way you got the APK
on — USB transfer or Google Drive — then drop it wherever the laptop pipeline
expects incoming bundles.

## Spec

See `C:\Users\shave\orbit-studio\docs\PHONE_CAPTURE_SPEC.md` for the full capture and bundle format spec.

## Fonts

The Home Sketch design system (`ui/theme/SketchTheme.kt`) uses IBM Plex Sans and IBM Plex Mono, © IBM, licensed under the SIL Open Font License 1.1.
