# Building LastScroll

Technical notes for building, installing, debugging and releasing.

## Behavior details

- The settings screen lists, top to bottom: "Start timer" (shown once
  everything is set up) or "Stop timer" (while a timer runs), "Required
  permissions" (only while a permission is missing, listing just the missing
  ones with a Grant button), "Sleep timer", "Apps", and "Add to home screen".
- If Android blocks the accessibility permission, the screen shows the
  steps to allow it (Open app info, three-dot menu, Allow restricted
  settings).
- Tap "Sleep after idle" to pick the minutes on a scroll wheel (default 20).
  Under Apps, tap "Add apps" to pick the apps that should keep the timer
  alive (search, check, Save).
- Start the timer with "Start timer", the home screen shortcut, or the
  launcher long-press shortcut "Start sleep timer".
- An ongoing notification shows "Screen off in N min" (whole minutes, no
  seconds countdown), with a Cancel action. Swiping it away also cancels.
  A reset that changes the minutes left shows a "Sleep timer reset" toast.
- "Status bar shows" picks the status bar icon: the app icon, the minutes left
  as a number, or (default) the icon plus the minutes left as an Android 16
  Live Update chip, falling back to the number if Live Updates are off or
  unsupported.
- Near the end, a toast shows the minutes left once per minute
  ("Screen off in 3 min" ... "Screen off in 1 min"). The final window is 20%
  of the configured minutes, rounded up, between 1 and 5 minutes. A reset
  that pushes the remaining time back above the window stops the toasts.
- Pausing: when you switch to an app that is not selected (or to the home
  screen), the timer pauses and the notification shows "Paused - N min left".
  Returning to a selected app resumes from the frozen remaining time; it does
  not reset to full. The notification shade, keyboards and LastScroll itself
  never pause the timer. A timer started while no selected app is in front
  starts paused. If the accessibility service is off, the timer never pauses.
- On expiry: media is paused, then (if the accessibility service is on) the
  phone goes home and locks the screen. If the service is off, a notification
  says the screen could not be turned off.

## Build

    ./gradlew assembleDebug

Needs JDK 17 and an Android SDK (platform 36, build-tools 36). Point to the
SDK with `sdk.dir=...` in `local.properties` or the `ANDROID_HOME` variable.

The APK is written to `app/build/outputs/apk/debug/lastscroll-<version>-debug.apk`. The version comes from `versionName` in `app/build.gradle.kts`; bump it (and `versionCode`) for every release.

## Install

    adb install -r app/build/outputs/apk/debug/lastscroll-<version>-debug.apk

## Enable the accessibility service

1. Open LastScroll; while the status shows Off, tap "Turn on", read the
   disclosure and tap Agree.
2. Android 13 and later block accessibility for sideloaded apps until you
   allow it: open Settings > Apps > LastScroll (App info), tap the menu
   (three dots) and choose "Allow restricted settings". Then try again.
3. In Accessibility settings, open LastScroll and turn it on.

## Permissions

- Accessibility service: observes taps, scrolls and window changes in the
  selected apps and volume key presses, only to reset or pause the timer, and
  performs "home" and "lock screen" on expiry. Keys are never consumed.
- POST_NOTIFICATIONS: shows the timer notification (Android 13+).
- POST_PROMOTED_NOTIFICATIONS: lets the timer show as a Live Update chip
  in the status bar (Android 16+).
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE: keeps the timer
  running while you use other apps.
- Package visibility is limited to launchable apps (a `<queries>` filter),
  used to list apps to choose from.
- No INTERNET permission.

## Verify resets

Each reset, pause and resume is logged:

    adb logcat -s LastScroll

Use this to check, for example, whether autoplay in an app resets the timer
without you touching it.

## Release signing

`./gradlew assembleRelease` builds a minified release APK. It is signed only
if a `keystore.properties` file exists in the project root (it is
gitignored):

    storeFile=/path/to/release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

Without it the release APK is unsigned.


## Releases (CI)

`.github/workflows/release.yml` builds, signs and publishes a GitHub Release
when a tag like `v1.2.3` is pushed. The tag (without the `v`) must equal
`versionName`, or the build fails. Running the workflow by hand (Actions >
Release > Run workflow) builds and signs the APK and uploads it as a
workflow artifact, without creating a release.

To cut a release:

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Commit, then tag: `git tag vX.Y.Z`
3. Push the commit and the tag: `git push origin main vX.Y.Z`

The workflow needs these repository secrets (Settings > Secrets and
variables > Actions > New repository secret):

- `LASTSCROLL_KEYSTORE_BASE64`: the release keystore, base64-encoded
  (`base64 -w0 lastscroll-release.jks`)
- `LASTSCROLL_KEYSTORE_PASSWORD`: the keystore password
- `LASTSCROLL_KEY_ALIAS`: the key alias
- `LASTSCROLL_KEY_PASSWORD`: the key password (same as the keystore
  password for a PKCS12 keystore)

The workflow decodes the keystore into the runner's temp directory and
writes `keystore.properties` only for the build, then deletes both.

Back up the keystore file and its passwords somewhere safe. If they are
lost, new versions cannot be signed with the same key, and Android will not
install an update over an existing install.
