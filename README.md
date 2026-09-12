# ImuxLauncher

Base Android launcher for a root-capable device.

## Current base

- Android launcher HOME activity.
- Enumerates installed launcher applications.
- Opens applications from the launcher.
- Requests session root through the device's `su` implementation.
- Explicit granted/denied root state in the UI.
- No Magisk module, boot modification, exploit, or persistence mechanism.
- GitHub Actions builds a debug APK and publishes it as an artifact.

## Root model

The launcher does not obtain root by exploiting the device. It invokes `su`; the installed root manager decides whether to grant or deny the request. If the device's temporary root disappears after reboot, the launcher simply reports root as unavailable until root is activated again.

## Build

```bash
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`
