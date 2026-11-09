# SleepTalk

An Android app that listens through the night and saves short WAV clips whenever it hears
sound above a threshold, so you can find out if (and what) you talk in your sleep.

- Records in a foreground service (16 kHz mono PCM) with a rolling pre-roll buffer, so each clip
  starts a few seconds *before* the sound that triggered it.
- Configurable sensitivity, pre-roll, post-silence cut-off, activation delay, auto-calibration to
  room noise, and auto-deletion of old clips.
- Fully offline: the app has no internet permission. Clips are stored in app-specific storage and
  can be played back or shared from inside the app.

## Requirements

| Tool        | Version                                               |
|-------------|-------------------------------------------------------|
| JDK         | **17** (Gradle 8.13 does not run on newer JDKs, e.g. 25) |
| Android SDK | Platform 34 (`compileSdk`/`targetSdk` 34)             |
| Gradle      | 8.13, via the included wrapper. No separate install needed |
| Device      | Android 8.0 (API 26) or newer                         |

Android Studio (recent stable) bundles a compatible JDK and can install the SDK for you, so it is
the easiest route.

## Setup

### Android Studio

1. Clone the repo and open the project folder in Android Studio.
2. Let it sync. It creates `local.properties` pointing at your SDK and offers to install any
   missing SDK components.
3. If sync complains about the JDK, set **Settings → Build, Execution, Deployment → Build Tools →
   Gradle → Gradle JDK** to a JDK 17 (the bundled JetBrains Runtime works).
4. Pick a device and press Run.

### Command line

1. Install JDK 17 and the Android SDK (platform 34 and build-tools), e.g. via Android Studio's SDK
   Manager or `sdkmanager`.
2. Tell Gradle where the SDK is, either with an environment variable:

   ```sh
   export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS default location
   ```

   or with a `local.properties` file in the project root (it is git-ignored):

   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```

3. Point Gradle at JDK 17, either with `JAVA_HOME`:

   ```sh
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS
   ```

   or by adding `org.gradle.java.home=/path/to/jdk-17` to your **user-level**
   `~/.gradle/gradle.properties`. Don't add it to the project's `gradle.properties`, because that
   path is machine-specific.

## Building

```sh
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # build and install on a connected device/emulator (adb)
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release-unsigned.apk
```

`gradlew` is a POSIX shell script (macOS/Linux). On Windows, use Android Studio or WSL.

### Release builds

The release build is minified with R8 but **unsigned**, since no signing config is committed. To
install it you need to sign it yourself, for example:

```sh
keytool -genkeypair -v -keystore release.jks -alias sleeptalk -keyalg RSA -keysize 2048 -validity 10000
$ANDROID_HOME/build-tools/<version>/apksigner sign --ks release.jks \
  --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Keep keystores out of git (`*.jks` and `*.keystore` are ignored).

## Using the app

On first start the app asks for:

- **Microphone**: required to record.
- **Notifications** (Android 13+): for the ongoing "listening" notification and the
  end-of-session summary.

Start a session before going to sleep and leave the phone near the bed (ideally on a charger).
Clips are saved as WAV files in the app's external files directory:

```
/sdcard/Android/data/is.juni.sleeptalk/files/clips/sleep_<timestamp>.wav
```

To copy them all off the device:

```sh
adb pull /sdcard/Android/data/is.juni.sleeptalk/files/clips ./clips
```

Uninstalling the app deletes the clips.

## Project layout

```
app/src/main/kotlin/is/juni/sleeptalk/
├── MainActivity.kt                 # Jetpack Compose UI: start/stop, live level meter, clip list, settings
├── service/
│   ├── AudioRecorderService.kt     # Foreground mic service: threshold detection, pre-roll, clip saving
│   └── CircularByteBuffer.kt       # Ring buffer holding the pre-roll audio
├── data/ClipRepository.kt          # Lists/deletes saved clips
└── util/WavEncoder.kt              # Writes PCM to WAV
```
