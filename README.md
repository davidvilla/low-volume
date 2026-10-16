# Lower volume

Android app that lets you lower the volume below the system minimum. It applies
a `DynamicsProcessing` effect with negative gain (0 to −50 dB) to the whole
audio output, no root required. With the system volume at 1, the hardware
volume keys keep going down by adding attenuation.

Requires Android 9 (API 28) or later.

## Requirements

Debian (or any x86_64 Linux) with `make`, `curl` and `unzip`:

```sh
sudo apt install make curl unzip
```

## Dependencies

```sh
make install-depends
```

Downloads whatever is missing and skips what is already installed:

- JDK 17 into `~/.local/share/jdk-17`
- Gradle 8.10.2 into `~/.local/share/gradle-8.10.2` (the Debian package is too
  old for the Android Gradle plugin)
- Android SDK (platform 35, build-tools and platform-tools) into
  `~/Android/Sdk`, accepting its licenses
- `local.properties`, which tells Gradle where the SDK is

The paths can be changed with
`make install-depends JDK_DIR=... SDK_DIR=... GRADLE_DIR=...` (pass the same
values to the other targets).

The project libraries (Android Gradle plugin, Kotlin) are downloaded by Gradle
into `~/.gradle` the first time it builds.

## Build

```sh
make build
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It is
signed with the debug key, which is enough to install it on your own phone.

`make build` runs `make install-depends` first, so on a fresh machine this
command is all you need. For static analysis: `make lint`.

## Install on the phone

Enable USB debugging on the phone, connect it, accept the authorization prompt
shown on screen and run:

```sh
make install
```

You can also copy the APK to the phone and install it from there, allowing
installs from unknown sources.

## Tests

```sh
make test          # unit tests, run on the computer
make test-device   # tests run inside the app installed on the phone
```

`make test` checks the volume rules (how the volume keys extend below the system
minimum, decibel conversion and formatting). It needs no phone.

`make test-device` installs the app on the connected phone, runs the
instrumented tests inside it and removes the test package afterwards. They
check that the effect is attached to the audio output, that the notification
actions and the volume keys change the reduction, and that the screen shows and
changes it. Keep the phone unlocked while they run: they press the volume keys
and change the volume, and restore your settings and volume when they finish.

## Clean

```sh
make clean       # removes the build output
make clean-all   # also removes the JDK, the SDK, Gradle and its cache
```

`make clean-all` deletes `~/.gradle/caches`, which is shared by all your Gradle
projects.
