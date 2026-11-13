# xiaomi-ai-glasses2grok-and-so-on

[中文](README.md)<br>
[Demo](https://www.bilibili.com/video/BV1jn456gEeQ/)

[![CI](https://github.com/slchy/xiaomi-ai-glasses2grok-and-so-on/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/slchy/xiaomi-ai-glasses2grok-and-so-on/actions/workflows/ci.yml)

Third-party Bluetooth SPP protocol specification and Android SDK driver implementation for Xiaomi AI Glasses (Model M2442G1).

---

## Technical Documentation & Specifications

- **Protocol Specification**: See [docs/a5a5.en.md](docs/a5a5.en.md) (L1 frame layout, sliding window, resynchronization, and SAR state machine).
- **Formal Verification**: See [docs/FORMAL.en.md](docs/FORMAL.en.md) (State machine invariants, GBN reliability, and counterexample exploration).
- **Capability Matrix**: See [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md) (58 hardware & protocol capabilities, evidence levels, and SDK coverage).
- **SDK Architecture**: See [docs/SDK-ARCHITECTURE.md](docs/SDK-ARCHITECTURE.md) (7-module decoupled architecture, lock-free concurrency model, lane backpressure, and state machine).
- **Frame Reference Implementation (Python)**: Located at `python/a5a5.py`.
- **Android SDK Source**: Located at `sdk/`.

---

## Android SDK

The SDK adopts a strict unidirectional layered architecture across 7 decoupled modules:

| Module | Type | Coordinate / Artifact | Responsibility |
|---|---|---|---|
| `:core-proto` | JVM | Internal implementation | Plumbing utilities: Varint, Hex, CRC16-ARC, MiniJson |
| `:core-api` | JVM | Internal API | Public contract layer: states, events, query DTOs, and constants |
| `:core` | JVM | Internal implementation | Core protocol engine: L1 parsing, L2 multiplexing, SAR, handshake auth |
| `:transport` | Android | Internal implementation | Bluetooth SPP socket I/O, pump threads, and broadcast listeners |
| `:facade` | Android | `com.miaiglasses:sdk` | **Main public AAR**: Exposes `GlassesSession` and high-level verbs |
| `:ktx` | Android | `com.miaiglasses:sdk-ktx` | **Optional Coroutines AAR**: Exposes `Flow<GlassesEvent>` and suspend utilities |
| `:sample` | Android | Demo app | One-page dashboard (connect / handshake / status / diagnostics / log copy) plus a protocol probe panel |

### Gradle Dependency

Nothing is published to Maven Central yet — build the artifacts into your local Maven repository first:

```bash
cd sdk
./gradlew publishToMavenLocal        # Windows: gradlew.bat publishToMavenLocal
```

Then make sure your project resolves `mavenLocal()` (add it to `pluginManagement` /
`dependencyResolutionManagement` repositories if your build restricts them):

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
    google()
}
dependencies {
    implementation("com.miaiglasses:sdk:0.1.1")
    implementation("com.miaiglasses:sdk-ktx:0.1.1") // Optional: Coroutines & Flow support
}
```

### Quick Start (Kotlin)

```kotlin
import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.sdk.GlassesSession

// 1. Obtain bonded BluetoothDevice and 16-byte authentication Token
val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice("...")
val token16: ByteArray = ... // 16-byte device token

// 2. Open session asynchronously (non-blocking, callable on any thread)
val session = GlassesSession.open(context, device, token16)

// 3. Register event listener
session.addListener { event ->
    when (event) {
        is GlassesEvent.StateChanged -> println("Session state: ${event.state}")
        is GlassesEvent.WearChanged  -> println("Wear status: ${event.status}")
        is GlassesEvent.BatteryChanged -> println("Battery: ${event.status.batteryPercent}%")
        is GlassesEvent.Image -> {
            println("Received photo preview: ${event.jpeg.size} bytes")
        }
        is GlassesEvent.Audio -> {
            // Live upstream voice stream (raw mSBC)
        }
        is GlassesEvent.Error -> {
            System.err.println("Error [${event.code}]: ${event.message}")
        }
        else -> Unit
    }
}

// 4. Invoke high-level capability verbs
session.capturePhoto() // Trigger photo capture
session.syncTime()     // Synchronize glasses time

// 5. Consume via Kotlin Flow (requires sdk-ktx)
// lifecycleScope.launch {
//     session.events().collect { event -> ... }
//     session.audio().collect { chunk -> ... }
// }

// 6. Graceful shutdown
session.close()
```

### Build & Test

**Requirements**: JDK 17 (AGP 8.5 floor) and an Android SDK (`compileSdk 34`, `minSdk 24`).
The Android modules need to locate it: set `ANDROID_HOME`, or write
`sdk.dir=<path>` into `sdk/local.properties` (that file is gitignored).

The SDK includes comprehensive JVM unit tests and golden vector regression suites:

```bash
cd sdk
./gradlew test          # all module unit tests (pure JVM simulation, no device needed)
./gradlew build         # additionally runs lint and assembles every AAR
```

---

## Python Quick Verification

For standalone verification of L1 frame serialization and deserialization, a zero-dependency Python reference implementation is provided:

```bash
python tests/test_frame.py
```

```python
from a5a5 import build, parse

frame = build(b"hi", ftype=3, seq=0)
frames, bad_crc, resync, dropped = parse(frame)
```

> Ensure `python/` is added to your `PYTHONPATH` before executing.

---

## Licensing

This project is licensed under a tiered multi-license structure. For details, see [LICENSE.md](LICENSE.md):

- **Protocol Specification & Frame Reference (`docs/`, `python/`)**: [CC0 1.0 Universal](LICENSES/CC0-1.0.txt) (Public Domain Dedication)
- **Android SDK Core Drivers (`sdk/core`, `transport`, `facade`, `ktx`)**: [PolyForm Noncommercial License 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt) (Noncommercial Research Only)
- **Sample Code (`sdk/sample/`)**: [MIT License](LICENSES/MIT.txt)

---

## Disclaimer

1. This project is an independent third-party open-source research initiative and is **not affiliated with, endorsed by, or sponsored by Xiaomi or Superhexa**.
2. All product names, trademarks, and registered trademarks mentioned are property of their respective owners and are used here solely for technical compatibility identification.
3. This project does not provide, extract, or store any device access credentials (Tokens). Users must ensure they have legal ownership and authorization for the connected hardware.