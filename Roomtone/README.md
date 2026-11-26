# Roomtone

Roomtone is a native Android sound monitor. It computes a sample-weighted mean-square input level over a rolling 1–60 second window and alerts only after the full window is available. The microphone stream is processed locally and is never saved or uploaded.

The displayed dBFS value is a relative digital input level, not calibrated sound-pressure dB. Microphone gain and device hardware affect the reading.

## Requirements

- JDK 17
- Android SDK Platform 36 and Android Build Tools installed through the SDK Manager
- Gradle wrapper downloads Gradle 8.13 on its first run

Android Studio is not required. The commands below use the Android SDK command-line tools on Linux. On another operating system, install the same SDK packages and use `gradlew.bat` from PowerShell or Command Prompt in place of `./gradlew`.

## Set up the toolchain

1. Install JDK 17. On Debian or Ubuntu:

	```sh
	sudo apt update
	sudo apt install -y openjdk-17-jdk unzip
	java -version
	```

2. Download the **Command line tools only** package from [Android Studio downloads](https://developer.android.com/studio#command-line-tools-only). Unzip it so `sdkmanager` is located at `$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`. For the Linux archive, the following commands put its contents in that location; replace the archive filename with the one you downloaded:

	```sh
	export ANDROID_HOME="$HOME/Android/Sdk"
	mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
	unzip commandlinetools-linux-*_latest.zip -d /tmp/roomtone-cmdline-tools
	mv /tmp/roomtone-cmdline-tools/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
	```

3. Configure the SDK environment and install the required packages. Accept the licenses when prompted:

	```sh
	export ANDROID_HOME="$HOME/Android/Sdk"
	export ANDROID_SDK_ROOT="$ANDROID_HOME"
	export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
	sdkmanager --sdk_root="$ANDROID_HOME" --licenses
	sdkmanager --sdk_root="$ANDROID_HOME" \
	  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
	```

	Add the `export` lines to `~/.profile` or your shell's equivalent to keep them for future terminals. Confirm the tools are available:

	```sh
	java -version
	sdkmanager --version
	adb version
	```

If Gradle cannot find the SDK, create `local.properties` in this project directory with its absolute path (this file is ignored by Git):

```sh
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

## Build and test

Run these commands from the `Roomtone/` project directory. The first Gradle run downloads Gradle 8.13 and the required Maven dependencies, so it needs network access:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The rolling-window unit tests cover delayed alert readiness until a full window exists, brief spikes, falling levels, sample-duration trimming, and reset behavior. To run tests and build the debug APK in one invocation:

```sh
./gradlew clean testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

In Windows PowerShell, the equivalent commands are:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

## Install on a device

Use an Android 8.0+ device. Enable Developer options and USB debugging, connect the device, accept its computer-authorization prompt, and confirm ADB sees it:

```sh
adb devices
```

Install directly with Gradle:

```sh
./gradlew installDebug
```

Or install the APK with ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.roomtone/.MainActivity
```

From Windows PowerShell, install through Gradle with:

```powershell
.\gradlew.bat installDebug
```

Grant microphone access when Roomtone asks. On Android 13 and later, also grant notification access so the ongoing monitor notification and its Stop action can be shown. Monitoring requires both permissions. You can grant them through Android Settings, or use these optional ADB commands on a connected device:

```sh
adb shell pm grant com.roomtone android.permission.RECORD_AUDIO
adb shell pm grant com.roomtone android.permission.POST_NOTIFICATIONS
```

The notification permission command applies to Android 13 and later; omit it on older versions. The app still needs to be opened and started by a visible user action.

## Test alerts

1. Open Roomtone and tap **Start monitoring**. Grant the requested permissions. Confirm the state changes to **ACTIVE** and the ongoing notification appears.
2. Wait for the selected averaging window to fill. Until then, Roomtone shows **Warming up** and deliberately does not alert.
3. In a quiet room, note the current dBFS reading. Set the threshold a few dB above that reading (a less-negative value), choose a 1–3 second window for a quick test, and select Tone, Vibration, or Both.
4. Speak close to the device or make a steady sound. The alert should begin only after the rolling average reaches the threshold, and stop after the average falls below it. A brief clap may be diluted by the averaging window; that is expected.
5. To test screen-off operation, start monitoring first, verify the notification, then lock the device and repeat the sound test. Stop monitoring from the app or the notification action.

Readings vary with each device's microphone gain and hardware. The dBFS value is a relative digital input level, not calibrated sound-pressure dB. For tone testing, ensure alarm volume is audible; if another app owns audio focus, choose Vibration or Both. Audio is analyzed on-device and is not recorded or uploaded.

Vendor battery-management policies may affect long-running foreground services. If monitoring stops after an extended screen-off period, exempt Roomtone from aggressive battery optimization and repeat the test.

## Troubleshooting

- `java: not found` or an incompatible Java version: install JDK 17 and verify `java -version` reports version 17.
- `SDK location not found`: export `ANDROID_HOME` as shown above or add the absolute SDK path to `local.properties`.
- `Failed to find Build Tools revision 36.0.0` or missing platform 36: rerun the `sdkmanager` package-install command and accept the SDK licenses.
- `adb devices` shows `unauthorized`: unlock the device and accept the USB debugging authorization prompt.
- The app does not start monitoring: grant microphone and notification permissions in Android Settings, return to Roomtone, and press Start while the app is visible.
- No tone is audible: check alarm volume and audio-focus contention; select Vibration or Both to test without relying on tone output.