# AADisplay-Beta (nickcatslin fork)

[![Release](https://img.shields.io/github/v/release/nickcatslin/AADisplay-Beta)](https://github.com/nickcatslin/AADisplay-Beta/releases/latest)
[![Build APK](https://github.com/nickcatslin/AADisplay-Beta/actions/workflows/build.yml/badge.svg)](https://github.com/nickcatslin/AADisplay-Beta/actions/workflows/build.yml)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK](https://img.shields.io/badge/Android%20SDK-min%2031%20%C2%B7%20target%2036-brightgreen?logo=android)
![Android Auto](https://img.shields.io/badge/Android%20Auto-17.7-orange)

An Xposed / LSPosed module that mirrors almost any phone app onto the Android Auto screen through a VirtualDisplay.

This is a personal fork, maintained to keep the module working on current Android and Android Auto releases. It is based on [`koalaauto/AADisplay-Beta`](https://github.com/koalaauto/AADisplay-Beta), which is itself a fork of [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay). The upstream fork is archived; its authors moved on to [KoalaMirror](https://github.com/koalaauto/KoalaMirror).

## Lineage

| Period | Repository | Highlights |
|---|---|---|
| 2024 – 2025 | [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay) | Original module: VirtualDisplay mirroring, the car-side launcher and controller, DPI and Android Auto property injection. |
| 2025 – 2026 | [`koalaauto/AADisplay-Beta`](https://github.com/koalaauto/AADisplay-Beta) | Android Auto 16.x support, portrait car screens, a reworked action bar, and a crash fix for Android 16 QPR2. Archived in mid 2026. |
| Sept 2026 – | **this fork** | Android 17 and Android Auto 17.6 / 17.7 support, a rebuilt "Screen Off Only" mode, and CI builds. See below. |

## What this fork changes

### Android Auto 17.6 / 17.7

- **Auto Open works again.** `CarSystemUiControllerService` no longer exists in AA 17.7. Auto Open now starts AADisplay through the same internal path Android Auto uses when you tap an app in its launcher. It fires once per projection session, so switching back to Maps is not undone.
- **Layout hooks follow the new layout types.** AA 17.7 changed the layout type from an enum back to an integer. The vertical rail and the portrait (bottom bar) layouts are rewritten for both forms.
- **Force Right Angle** matches the 12-argument `ProjectionWindowDecorationParams` constructor used since AA 17.6.

### Android 17

- **Screen Off Only (rebuilt).** With this option on, the phone sleeps normally (power key, timeout, lock screen) while the car keeps showing the mirrored app and stays fully touchable. Android 17 ignores `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` for regular virtual displays, so the module keeps its own display awake and interactive inside system_server instead. This covers the display power policy, the display state, and the display's input interactivity, and it also works when AADisplay starts while the phone is already asleep.
- **Recent-task thumbnails** use the new `TaskSnapshotManager` API.
- **Removed window manager APIs** (such as `setShouldShowSystemDecors`) no longer abort display setup.

### Stability and defaults

- Uncaught coroutine exceptions can no longer bring down system_server.
- The module falls back to default settings when its config file does not exist yet.
- The default launch app is Google Maps and the default Home app is [Car Launcher](https://play.google.com/store/apps/details?id=com.autolauncher.motorcar.free).
- The keyboard setting now reads "Car screen" or "Phone screen" to show where the keyboard appears.

### Builds

GitHub Actions builds a debug APK on every push. It also builds a release APK when the signing secrets are configured.

## Requirements

- Android 12 or later (SDK 31+). Developed and tested on a Pixel running Android 17.
- A rooted device with **LSPosed** or a compatible Xposed framework.
- Android Auto. This fork is tested with 17.7; 17.6 is supported.

> Some ROMs may be unstable or crash. Use at your own risk.

## Install and set up

1. Install the APK from the latest [release](https://github.com/nickcatslin/AADisplay-Beta/releases/latest). Development builds are also available as artifacts of the [Build APK workflow](https://github.com/nickcatslin/AADisplay-Beta/actions/workflows/build.yml).
2. In **LSPosed**, enable the module and scope it to **System Framework** and **Android Auto**.
3. Reboot the phone. The system-side hooks only load at boot.
4. Open AADisplay once so it creates its settings file, then adjust the settings as needed.
5. Connect to the car. With **Auto Open** on, AADisplay appears about a second after the Android Auto action bar.

## Settings

| Setting | What it does |
|---|---|
| Auto Open | Opens AADisplay automatically once per projection session. |
| Default Launch Package | App started on the car screen when the display is created. Empty means Google Maps. |
| Home Package | App started by the Home key. Empty means Car Launcher. |
| Android Auto DPI / Mirrored Display DPI | Density overrides. 0 keeps the default. |
| Keep Display Alive After Exit | Seconds the mirrored display survives after Android Auto disconnects. |
| Block AA Split Screen | Turns a tap on the launcher/dashboard icon into a long press, so it opens the app list instead of the split screen. |
| Force Right Angle | Removes the rounded corners of the projected window. |
| Screen Off Only | Lets the phone sleep while the car keeps projecting and accepting touch. Reconnect after changing it. |
| Keyboard (IME) Display | Shows the keyboard on the car screen or on the phone screen. Reconnect after changing it. |
| Voice Assistant Shell | Root shell command run by the assistant key. Empty means the built-in Google Assistant. |
| Shell Commands | Root commands run before the display is created and after it is destroyed. |
| Advanced | Android Auto and Play Services car property overrides. |

Root is only used for the shell commands you configure. Deny it if you do not need them.

## Recent tasks on the car

- The left column lists apps running on the car screen. The right column lists apps on the phone.
- Tap an item to bring it to the front of the screen it is already on.
- Swipe a phone app to the left to move it to the car screen. Swipe a car app to the right to move it back to the phone.
- Swipe the other way to close the app.

## Building

The repository does not ship a Gradle wrapper. You need JDK 17, the Android SDK (platforms 33 and 36, build-tools 35), and Gradle 8.13 or later.

```sh
gradle :aa-display:assembleDebug
```

To sign a build with the release key, set the keystore password in the `KEY_ANDROID` environment variable. The keystore is `key.jks` with the alias `key0`.

## Credits

- [@Nitsuya](https://github.com/Nitsuya) for the original AADisplay.
- The [koalaauto](https://github.com/koalaauto) maintainers for the AADisplay-Beta fork, and its contributors.

## License

GPL-3.0, same as upstream. See `LICENSE`.
