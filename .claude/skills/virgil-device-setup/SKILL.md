---
name: virgil-device-setup
description: Install Virgil on an attached phone or emulator and drive the UI over adb to enable a provider and enter its API key, without the key touching argv, logs or the repo. Use after a fresh install or `pm clear`, when the app shows "No providers chosen" or "No key for: <vendor>".
---

# Install Virgil and enter a provider key over adb

A fresh install starts with no providers and no keys (see "No secrets anywhere
near the repo" in `CLAUDE.md`). This is the procedure for getting from there to
a runnable app without tapping on the phone by hand. The human version of the
same procedure is "Installing on a phone" and "Using it" in `README.md`; change
both together.

The generic mechanics - driving by label, calibrating checks, passing secrets
on stdin - come from the `skillz:android-adb-device-verification` skill and its
`scripts/adb-ui.sh` helper. Commands below assume `UI` points at that script.

## 1. Install the release build

```bash
./gradlew assembleRelease
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

`--user 0` matters on a phone with a work profile: without it, `pm` and
`dumpsys` calls that resolve the current user can fail with
`Shell does not have permission to access user 10`.

## 2. Launch and enable the provider

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell monkey -p ai.voitta.virgil -c android.intent.category.LAUNCHER 1
$UI text      # expect "Providers: none chosen" and the provider list
```

Switches have no text of their own, so tap them by position. They follow
`PROVIDER_CATALOG` order in `Vendors.kt` (openrouter, gemini, gemini-compat,
groq, deepseek):

```bash
$UI tap-nth 2 'checkable="true"'     # 2 = gemini
$UI text      # expect "Providers: gemini" and "<vendor> API key"
```

The order you switch providers on is the waterfall order.

## 3. Enter the key

Take the key from your own environment, never from the repo, and feed it on
stdin so it does not appear in `ps` or shell history:

```bash
$UI tap "gemini API key"
printf '%s\n' "$GEMINI_API_KEY" | $UI type
adb shell input keyevent KEYCODE_BACK    # dismiss the keyboard so Save is visible
$UI tap "Save key"
```

## Done when

`$UI text` shows "Providers: <vendor>" and no longer shows
"No key for: <vendor>". That only proves the key was stored. To prove it is
valid, tap "Where am I?" and check that a blurb appears, or that the walk in
the log shows the vendor answering.

## Undo

`adb shell pm clear ai.voitta.virgil` wipes the stored key. Do this on any
device that is not yours.
