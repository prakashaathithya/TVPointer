# TV Pointer

Turns your Android phone into a **Bluetooth mouse and keyboard** for Google TV / Android TV.
The TV shows its own mouse cursor. **Nothing is installed on the TV.**

Needs Android 9 or newer on the phone, and a phone maker that hasn't disabled the Bluetooth "HID device" feature (the app tells you on first launch).

## Trackpad gestures

| Gesture | Action on TV |
|---|---|
| One-finger drag | Move cursor (faster swipe = travels further) |
| Tap | Click |
| Press and hold | Long press; keep holding and move to drag |
| Two-finger drag | Scroll |
| Two-finger tap | Back |

Buttons: **Back**, **Home**, **Keyboard**, plus a pointer-speed slider.

## Keyboard

Tap the keyboard button, click a text box on the TV with the cursor, and type on your phone. Typing, autocorrect and swipe typing are sent as real key presses. ⌫ = Backspace, ↵ = Enter.
A Bluetooth keyboard uses a US key layout, so only English letters, numbers and symbols can be sent (no Tamil/Hindi script or emoji).

## First-time pairing

1. Open TV Pointer on the phone and allow Bluetooth.
2. Tap **Make phone visible**.
3. On the TV: **Settings → Remotes & Accessories → Pair remote or accessory**, choose your phone, accept the code on both screens.
4. The trackpad opens automatically. Next time, just open the app — it reconnects to the last TV.

## Getting the APK (no Android Studio needed)

Push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` builds `TvPointer.apk` on every push and publishes it on the repository's **Releases** page. Download it on the phone and install (allow "Install unknown apps" for your browser when asked).

## Code

- `HidReports.kt` – HID descriptor (keyboard, mouse, Home/Back keys) and US key map
- `HidRemote.kt` – registers the phone as a Bluetooth HID device and sends reports
- `Trackpad.kt` – gesture detection
- `RemoteScreen.kt`, `PairScreen.kt`, `MainActivity.kt` – UI
