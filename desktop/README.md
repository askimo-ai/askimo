# Askimo Desktop

A Kotlin Compose Desktop application for the Askimo project.

## Features

- Modern Material Design 3 UI
- Simple chat interface with message bubbles
- User and AI message differentiation
- Responsive layout with scrollable message area

## Requirements

- JDK 21 or higher
- Gradle 8.x or higher

## Running the Application

To run the application in development mode:

```bash
./gradlew desktop:run
```

## Building the Application

To build the application:

```bash
./gradlew desktop:build
```

## Localization

### Detecting Unused Localization Keys

To find unused localization keys in your properties files:

```bash
./gradlew :desktop:detectUnusedLocalizations
```

This task will:
- Scan all localization keys in `messages.properties`
- Check usage across both desktop and shared modules
- Generate a detailed report at `build/reports/unused-localizations.txt`
- Display a summary with any unused keys

## Creating Native Distributions

You can create native distributions for different platforms:

```bash
# Create DMG for macOS
./gradlew desktop:packageDmg

# Create MSI for Windows
./gradlew desktop:packageMsi

# Create DEB for Linux
./gradlew desktop:packageDeb
```

## Project Structure

```
desktop/
├── build.gradle.kts          # Build configuration
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── io/askimo/desktop/
│       │       └── Main.kt    # Main application code
│       └── resources/         # Resources (icons, etc.)
└── README.md                  # This file
```

## Architecture

The application uses:
- **Compose Multiplatform**: For building the UI
- **Material 3**: For modern design components
- **Kotlin Coroutines**: For asynchronous operations

## Voice Playback (MP3 support)

The optional voice feature (Settings > Voice) plays back synthesized speech via
[`javax.sound.sampled`](https://docs.oracle.com/javase/8/docs/technotes/guides/sound/), which
only decodes WAV/AIFF/AU natively. OpenAI's TTS API returns **MP3**-encoded audio, so MP3
playback requires a third-party `javax.sound.sampled` SPI provider on the runtime classpath:

- **Dependency**: [`com.googlecode.soundlibs:mp3spi`](https://mvnrepository.com/artifact/com.googlecode.soundlibs/mp3spi)
  (declared once in `desktop-shared/build.gradle.kts` via the `mp3spi` version-catalog entry).
  Its POM transitively pulls in `tritonus-share` + `jlayer`, so no other libraries need to be
  added by hand.
- **Packaging**: no extra jpackage/module-path configuration is needed — it's a plain jar on the
  classpath (registered via `META-INF/services/javax.sound.sampled.spi.*`), so `packageDmg` /
  `packageMsi` / `packageDeb` all bundle it automatically like any other dependency.
- **Local (Piper) TTS** returns WAV instead, which needs no extra SPI.
- Covered by `io.askimo.ui.voice.Mp3DecodingTest` in `desktop-shared`'s test suite, which fails
  loudly with an actionable message if this dependency is ever accidentally removed.
