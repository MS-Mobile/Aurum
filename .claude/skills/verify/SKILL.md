---
name: verify
description: Build, install, and drive the Aurum camera app on a phone or emulator to verify changes at the UI surface
---

# Verifying Aurum changes on a device

## Build & install

```bash
adb devices                                   # phone (wireless debugging) or emulator
./gradlew :app:installDebug                   # applicationId: com.msmobile.aurum
adb shell pm grant com.msmobile.aurum android.permission.CAMERA
adb shell am start -n com.msmobile.aurum/.MainActivity
```

- Debug builds carry arm64-v8a and x86_64 (emulator); release is arm64-v8a only.
- Debug processing is ~4x slower than release because ART doesn't fully compile debuggable apps. For
  timings use the release build (`assembleRelease`, debug-signed locally) or `tools/run-reference-test.sh`.
- The x86_64 emulator image uses 16 KB pages and shows a one-off compatibility dialog for the native
  libraries; tap OK. It does not appear on the phone.

## Drive & capture

- Screenshot: `adb exec-out screencap -p > shot.png`.
- Find control bounds instead of guessing coordinates:
  `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`, then look for the
  content descriptions "Take photo", "Open last photo" and "Switch camera".
- Processing result: `adb logcat -d | grep PhotoProcessor` prints size, EXIF rotation, EV100, the HDR
  strength used, total time and the saved MediaStore URI.
- Saved photos: `adb shell ls -t /sdcard/Pictures/Aurum/`. In Git Bash set `MSYS_NO_PATHCONV=1`, or
  device paths get rewritten to `C:/Program Files/Git/...`.

## Gotchas

- Check `dumpsys activity activities | grep topResumedActivity` before tapping: a BACK keyevent can leave
  the app, and a blind tap then lands on another app's launcher icon.
- HyperOS asks for confirmation on every adb install unless "always allow" was ticked;
  `connectedAndroidTest` uninstalls afterwards and so re-prompts every run. `tools/run-reference-test.sh`
  avoids that.
- Restarting the adb server drops a wireless-debugging connection; the user has to toggle wireless
  debugging to reconnect.
- To check selfie mirroring, compare a screencap of the front preview (mirrored on screen) with the saved
  photo: the saved one must match only after a horizontal flip.
