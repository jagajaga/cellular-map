# Signal Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that records dual-SIM signal strength while walking and renders a smooth zoom-adaptive gradient over OSM; built and released as installable APKs by GitHub Actions from a public repo.

**Architecture:** Single-module Kotlin app. Foreground service samples GPS + per-SIM signal into Room/SQLite (with web-mercator-quantized integer coords). Rendering aggregates cells via SQL `GROUP BY` bit-shift, splats them into a low-res value/weight field, IDW-normalizes, colorizes through a dBm LUT, and draws the bitmap as an osmdroid overlay. CI signs every APK with a committed keystore and monotonically increasing `versionCode` so installs always update in place.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, minSdk 29 / target 35, osmdroid 6.1.20, Room 2.6.1 (KSP 2.0.21-1.0.27), play-services-location 21.3.0, Material 1.12.0, JUnit4.

## Global Constraints

- minSdk 29 (Android 10+); compileSdk/targetSdk 35.
- Package/appId: `me.jagajaga.signalmap`; repo: public `jagajaga/cellular-map`.
- Signal metric: dBm; render window −120 dBm (red) → −70 dBm (green); no-data = transparent, never red.
- Aggregation for display: `MAX(dbm)` per cell (best-spot goal); `AVG` also selected.
- Finest rendered cell ground size ≥ 2 m (`shift >= 6` at z30 storage coords); cells ≈ 24–32 screen px otherwise.
- Samples with GPS accuracy > 25 m: stored with `flagged = 1`, excluded from rendering/aggregation.
- All APKs (debug + release) signed with committed keystore `app/signalmap.keystore` (store/key password `signalmap`, alias `signalmap`); `versionCode` = `GITHUB_RUN_NUMBER` (fallback 1) so every CI build installs over the previous one without uninstalling.
- No server, no analytics, no Play Store. Export = CSV + GeoJSON via share sheet.
- Local toolchain lives under `~/toolchain` (JDK, Android SDK, Gradle); CI (`ubuntu-latest`) is the authoritative build. If a local step cannot run, push and verify with `gh run watch`.
- Commit subjects follow the `naming-commits` skill (`type(scope): ...`, no co-author trailers).

---

### Task 0: Local toolchain bootstrap

**Files:**
- Create: `~/toolchain/` (outside repo), `local.properties` (gitignored), `.gitignore`

**Interfaces:**
- Produces: `java` (17), `sdkmanager`-installed Android SDK at `~/toolchain/android-sdk`, `gradle` 8.9 on PATH for wrapper generation. Later tasks run `./gradlew` with `JAVA_HOME=~/toolchain/jdk17`.

- [ ] **Step 1: Download JDK 17 (Temurin) and Gradle 8.9**

```bash
mkdir -p ~/toolchain && cd ~/toolchain
curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o jdk17.tar.gz
mkdir jdk17 && tar xzf jdk17.tar.gz -C jdk17 --strip-components=1
curl -sLO https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip -q gradle-8.9-bin.zip
export JAVA_HOME=~/toolchain/jdk17 PATH=~/toolchain/jdk17/bin:~/toolchain/gradle-8.9/bin:$PATH
java -version && gradle --version
```
Expected: `openjdk version "17...` and `Gradle 8.9`.

- [ ] **Step 2: Install Android SDK command-line tools + platform**

```bash
cd ~/toolchain
curl -sLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p android-sdk/cmdline-tools && unzip -q commandlinetools-linux-*.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```
Expected: packages install without error.

- [ ] **Step 3: Repo-side plumbing**

`.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
captures/
```
`local.properties`:
```
sdk.dir=/home/jaga/toolchain/android-sdk
```
No commit yet (committed with Task 1 skeleton).

**Fallback:** if downloads fail (network/disk), skip local builds entirely; every "run gradle" step becomes "push branch, `gh run watch`".

---

### Task 1: Project skeleton, keystore, signing

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*` (via `gradle wrapper`), `app/build.gradle.kts`, `app/signalmap.keystore`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/java/me/jagajaga/signalmap/ui/MainActivity.kt` (placeholder), `.gitignore`, `local.properties`

**Interfaces:**
- Produces: building app module; signing config `shared` used by both build types; `./gradlew test assembleRelease` green.

- [ ] **Step 1: Generate wrapper and keystore**

```bash
cd ~/code-workspace/cellular-map
gradle wrapper --gradle-version 8.9
keytool -genkeypair -v -keystore app/signalmap.keystore -alias signalmap \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass signalmap -keypass signalmap -dname "CN=Signal Map"
```

- [ ] **Step 2: Write Gradle files**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "cellular-map"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
kotlin.code.style=official
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "me.jagajaga.signalmap"
    compileSdk = 35
    defaultConfig {
        applicationId = "me.jagajaga.signalmap"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }
    signingConfigs {
        create("shared") {
            storeFile = file("signalmap.keystore")
            storePassword = "signalmap"
            keyAlias = "signalmap"
            keyPassword = "signalmap"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug { signingConfig = signingConfigs.getByName("shared") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: Manifest, resources, placeholder activity**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.SignalMap"
        android:allowBackup="true">
        <activity android:name=".ui.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Signal Map</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.SignalMap" parent="Theme.Material3.DayNight.NoActionBar" />
</resources>
```

`app/src/main/java/me/jagajaga/signalmap/ui/MainActivity.kt` (placeholder, replaced in Task 7):
```kotlin
package me.jagajaga.signalmap.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

- [ ] **Step 4: Build**

```bash
export JAVA_HOME=~/toolchain/jdk17 PATH=$JAVA_HOME/bin:$PATH
./gradlew test assembleRelease
```
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: add project skeleton"
```

---

### Task 2: Public repo + CI workflow

**Files:**
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Produces: on every push — artifact `signalmap-apk`; on tag `v*` — GitHub Release with the APK attached.

- [ ] **Step 1: Write workflow**

`.github/workflows/android.yml`:
```yaml
name: android
on:
  push:
    branches: [main]
    tags: ["v*"]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew test assembleRelease
      - name: Rename APK
        run: cp app/build/outputs/apk/release/app-release.apk signalmap.apk
      - uses: actions/upload-artifact@v4
        with:
          name: signalmap-apk
          path: signalmap.apk
      - uses: softprops/action-gh-release@v2
        if: startsWith(github.ref, 'refs/tags/')
        with:
          files: signalmap.apk
```

- [ ] **Step 2: Create public repo and push**

```bash
gh repo create jagajaga/cellular-map --public --source . --push
```

- [ ] **Step 3: Verify CI green + artifact exists**

```bash
gh run watch --exit-status $(gh run list -L1 --json databaseId -q '.[0].databaseId')
gh run view $(gh run list -L1 --json databaseId -q '.[0].databaseId') --json jobs -q '.jobs[].conclusion'
```
Expected: `success`.

- [ ] **Step 4: Commit** (workflow was included in push; if edited to fix CI, commit as `ci: fix android workflow`).

---

### Task 3: Mercator quantization (pure)

**Files:**
- Create: `app/src/main/java/me/jagajaga/signalmap/render/Mercator.kt`
- Test: `app/src/test/java/me/jagajaga/signalmap/render/MercatorTest.kt`

**Interfaces:**
- Produces: `Mercator.lonToX(lon: Double): Int`, `Mercator.latToY(lat: Double): Int`, `Mercator.xToLon(x: Int): Double`, `Mercator.yToLat(y: Int): Double` (z30 world-pixel coords, 0..2^30), `Mercator.shiftForZoom(zoom: Double): Int` (= `max(27 - zoom.toInt(), 6)`).

- [ ] **Step 1: Failing test**

```kotlin
package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Test

class MercatorTest {
    @Test fun roundTripBerlin() {
        val lat = 52.52; val lon = 13.405
        assertEquals(lat, Mercator.yToLat(Mercator.latToY(lat)), 1e-5)
        assertEquals(lon, Mercator.xToLon(Mercator.lonToX(lon)), 1e-5)
    }
    @Test fun originIsCenter() {
        assertEquals(1 shl 29, Mercator.lonToX(0.0))
        assertEquals(1 shl 29, Mercator.latToY(0.0))
    }
    @Test fun monotonic() {
        assert(Mercator.lonToX(10.0) < Mercator.lonToX(10.001))
        assert(Mercator.latToY(50.0) > Mercator.latToY(50.001)) // y grows southward
    }
    @Test fun shiftClamps() {
        assertEquals(6, Mercator.shiftForZoom(22.0))
        assertEquals(6, Mercator.shiftForZoom(21.0))
        assertEquals(7, Mercator.shiftForZoom(20.0))
        assertEquals(13, Mercator.shiftForZoom(14.0))
    }
}
```

- [ ] **Step 2: Run — expect FAIL (unresolved reference `Mercator`)**: `./gradlew test --tests '*MercatorTest*'`

- [ ] **Step 3: Implement**

```kotlin
package me.jagajaga.signalmap.render

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Web-mercator integer coords at "pixel zoom 30" (256px tiles * 2^22). ~3.7cm/unit at equator. */
object Mercator {
    private const val WORLD = 1L shl 30
    private const val MAX = (1 shl 30) - 1

    fun lonToX(lon: Double): Int =
        ((lon + 180.0) / 360.0 * WORLD).toLong().coerceIn(0, MAX.toLong()).toInt()

    fun latToY(lat: Double): Int {
        val s = sin(lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
        val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        return (y * WORLD).toLong().coerceIn(0, MAX.toLong()).toInt()
    }

    fun xToLon(x: Int): Double = x.toDouble() / WORLD * 360.0 - 180.0

    fun yToLat(y: Int): Double {
        val n = PI - 2.0 * PI * (y.toDouble() / WORLD)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /** Grid cell = 2^shift storage units; ~24-32 screen px at [zoom], floored at shift 6 (>=2m). */
    fun shiftForZoom(zoom: Double): Int = max(27 - zoom.toInt(), 6)
}
```

- [ ] **Step 4: Run — expect PASS**: `./gradlew test --tests '*MercatorTest*'`

- [ ] **Step 5: Commit**: `git commit -m "feat(render): add web-mercator quantization"`

---

### Task 4: Color map + heat field (pure)

**Files:**
- Create: `app/src/main/java/me/jagajaga/signalmap/render/ColorMap.kt`, `app/src/main/java/me/jagajaga/signalmap/render/HeatField.kt`
- Test: `app/src/test/java/me/jagajaga/signalmap/render/ColorMapTest.kt`, `app/src/test/java/me/jagajaga/signalmap/render/HeatFieldTest.kt`

**Interfaces:**
- Produces:
  - `ColorMap.norm(dbm: Int): Float` — clamps −120..−70 → 0..1
  - `ColorMap.argb(t: Float, alpha: Int): Int` — red→yellow→green
  - `class HeatField(val w: Int, val h: Int)` with `splat(cx: Float, cy: Float, value: Float, radius: Float)` and `colorize(maxAlpha: Int = 255): IntArray` (size w*h, 0 where no weight).

- [ ] **Step 1: Failing tests**

```kotlin
package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorMapTest {
    @Test fun normClamps() {
        assertEquals(0f, ColorMap.norm(-130), 1e-6f)
        assertEquals(1f, ColorMap.norm(-60), 1e-6f)
        assertEquals(0.5f, ColorMap.norm(-95), 1e-6f)
    }
    @Test fun endpointsAreRedAndGreen() {
        val red = ColorMap.argb(0f, 255)
        val green = ColorMap.argb(1f, 255)
        assertEquals(0xFF, (red shr 16) and 0xFF)   // full red channel
        assertEquals(0x00, red and 0xFF)             // no blue
        assertEquals(0xFF, (green shr 8) and 0xFF)  // full green channel
    }
    @Test fun midIsYellow() {
        val y = ColorMap.argb(0.5f, 255)
        assertEquals(0xFF, (y shr 16) and 0xFF)
        assertEquals(0xFF, (y shr 8) and 0xFF)
    }
}
```

```kotlin
package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatFieldTest {
    @Test fun emptyFieldIsTransparent() {
        val px = HeatField(4, 4).colorize()
        assertTrue(px.all { it == 0 })
    }
    @Test fun splatCenterHasValueColor() {
        val f = HeatField(9, 9)
        f.splat(4f, 4f, 1f, 3f)
        val px = f.colorize()
        val c = px[4 * 9 + 4]
        assertTrue((c ushr 24) > 0)                    // visible
        assertEquals(0xFF, (c shr 8) and 0xFF)         // green (t=1)
    }
    @Test fun interpolationBetweenTwoSplatsIsBetweenValues() {
        val f = HeatField(21, 5)
        f.splat(2f, 2f, 0f, 6f)
        f.splat(18f, 2f, 1f, 6f)
        // midpoint x=10 covered by neither kernel fully -> may be transparent; use x=5 inside left+edge of right
        val c = f.colorize()[2 * 21 + 5]
        val redCh = (c shr 16) and 0xFF
        assertTrue((c ushr 24) > 0 && redCh > 0)       // leaning red side, blended
    }
    @Test fun outsideRadiusIsTransparent() {
        val f = HeatField(20, 20)
        f.splat(2f, 2f, 1f, 3f)
        assertEquals(0, f.colorize()[19 * 20 + 19])
    }
}
```

- [ ] **Step 2: Run — expect FAIL**: `./gradlew test --tests '*ColorMapTest*' --tests '*HeatFieldTest*'`

- [ ] **Step 3: Implement**

`ColorMap.kt`:
```kotlin
package me.jagajaga.signalmap.render

object ColorMap {
    const val MIN_DBM = -120f
    const val MAX_DBM = -70f

    fun norm(dbm: Int): Float =
        ((dbm - MIN_DBM) / (MAX_DBM - MIN_DBM)).coerceIn(0f, 1f)

    /** 0 = red, 0.5 = yellow, 1 = green. */
    fun argb(t: Float, alpha: Int): Int {
        val tt = t.coerceIn(0f, 1f)
        val r: Int; val g: Int
        if (tt < 0.5f) { r = 0xFF; g = (tt * 2f * 255f).toInt() }
        else { r = ((1f - tt) * 2f * 255f).toInt(); g = 0xFF }
        return (alpha shl 24) or (r shl 16) or (g shl 8)
    }
}
```

`HeatField.kt`:
```kotlin
package me.jagajaga.signalmap.render

import kotlin.math.max
import kotlin.math.min

/**
 * Value/weight accumulation field for IDW-style smooth rendering.
 * splat() adds a radial kernel; colorize() divides value by weight per pixel
 * and maps through ColorMap, transparent where weight ~ 0.
 */
class HeatField(val w: Int, val h: Int) {
    private val valSum = FloatArray(w * h)
    private val wSum = FloatArray(w * h)

    fun splat(cx: Float, cy: Float, value: Float, radius: Float) {
        val r2 = radius * radius
        val x0 = max(0, (cx - radius).toInt()); val x1 = min(w - 1, (cx + radius).toInt())
        val y0 = max(0, (cy - radius).toInt()); val y1 = min(h - 1, (cy + radius).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            val d2 = dx * dx + dy * dy
            if (d2 >= r2) continue
            val q = 1f - d2 / r2
            val k = q * q
            val i = y * w + x
            valSum[i] += value * k
            wSum[i] += k
        }
    }

    fun colorize(maxAlpha: Int = 255): IntArray {
        val out = IntArray(w * h)
        for (i in out.indices) {
            val wt = wSum[i]
            if (wt < 1e-4f) continue
            val v = valSum[i] / wt
            val alpha = (maxAlpha * min(1f, wt / 0.25f)).toInt()
            out[i] = ColorMap.argb(v, alpha)
        }
        return out
    }
}
```

- [ ] **Step 4: Run — expect PASS**: `./gradlew test --tests '*ColorMapTest*' --tests '*HeatFieldTest*'`

- [ ] **Step 5: Commit**: `git commit -m "feat(render): add dbm color map and IDW heat field"`

---

### Task 5: Data layer (Room) + Exporter

**Files:**
- Create: `app/src/main/java/me/jagajaga/signalmap/data/Sample.kt`, `app/src/main/java/me/jagajaga/signalmap/data/SampleDao.kt`, `app/src/main/java/me/jagajaga/signalmap/data/AppDb.kt`, `app/src/main/java/me/jagajaga/signalmap/data/Exporter.kt`
- Test: `app/src/test/java/me/jagajaga/signalmap/data/ExporterTest.kt`

**Interfaces:**
- Produces:
  - `data class Sample(id: Long = 0, sessionId: Long, simSlot: Int, timestampMs: Long, lat: Double, lon: Double, accuracyM: Float, dbm: Int, networkType: String, mx: Int, my: Int, flagged: Int)`
  - `data class CellAgg(cx: Int, cy: Int, maxDbm: Int, avgDbm: Double, n: Int)`
  - `data class SessionRow(sessionId: Long, n: Int, startMs: Long, endMs: Long)`
  - `SampleDao.insertAll(List<Sample>)`, `aggregate(sim: Int, shift: Int, x0: Int, x1: Int, y0: Int, y1: Int): List<CellAgg>`, `sessions(): List<SessionRow>`, `samplesForSession(id: Long): List<Sample>`, `deleteSession(id: Long)`, `countForSession(id: Long): Int` — all suspend.
  - `AppDb.get(context): AppDb` singleton with `dao(): SampleDao`.
  - `Exporter.csv(samples: List<Sample>): String`, `Exporter.geoJson(samples: List<Sample>): String` (pure, no Android deps).

- [ ] **Step 1: Failing Exporter tests**

```kotlin
package me.jagajaga.signalmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {
    private val s = Sample(
        id = 1, sessionId = 1000, simSlot = 0, timestampMs = 1721990000000,
        lat = 52.52, lon = 13.405, accuracyM = 4.5f, dbm = -95,
        networkType = "LTE", mx = 1, my = 2, flagged = 0
    )

    @Test fun csvHasHeaderAndRow() {
        val out = Exporter.csv(listOf(s))
        val lines = out.trim().lines()
        assertEquals("sessionId,simSlot,timestampMs,lat,lon,accuracyM,dbm,networkType,flagged", lines[0])
        assertEquals("1000,0,1721990000000,52.52,13.405,4.5,-95,LTE,0", lines[1])
    }

    @Test fun geoJsonIsFeatureCollectionWithPoint() {
        val out = Exporter.geoJson(listOf(s))
        assertTrue(out.contains("\"FeatureCollection\""))
        assertTrue(out.contains("[13.405,52.52]"))
        assertTrue(out.contains("\"dbm\":-95"))
        assertTrue(out.contains("\"sim\":0"))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**: `./gradlew test --tests '*ExporterTest*'`

- [ ] **Step 3: Implement data layer**

`Sample.kt`:
```kotlin
package me.jagajaga.signalmap.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "samples",
    indices = [Index("simSlot", "mx", "my"), Index("sessionId")]
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val simSlot: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val dbm: Int,
    val networkType: String,
    val mx: Int,
    val my: Int,
    val flagged: Int
)

data class CellAgg(val cx: Int, val cy: Int, val maxDbm: Int, val avgDbm: Double, val n: Int)

data class SessionRow(val sessionId: Long, val n: Int, val startMs: Long, val endMs: Long)
```

`SampleDao.kt`:
```kotlin
package me.jagajaga.signalmap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert suspend fun insertAll(samples: List<Sample>)

    @Query(
        """SELECT (mx >> :shift) AS cx, (my >> :shift) AS cy,
                  MAX(dbm) AS maxDbm, AVG(dbm) AS avgDbm, COUNT(*) AS n
           FROM samples
           WHERE simSlot = :sim AND flagged = 0
             AND mx BETWEEN :x0 AND :x1 AND my BETWEEN :y0 AND :y1
           GROUP BY cx, cy"""
    )
    suspend fun aggregate(sim: Int, shift: Int, x0: Int, x1: Int, y0: Int, y1: Int): List<CellAgg>

    @Query(
        """SELECT sessionId, COUNT(*) AS n, MIN(timestampMs) AS startMs, MAX(timestampMs) AS endMs
           FROM samples GROUP BY sessionId ORDER BY sessionId DESC"""
    )
    suspend fun sessions(): List<SessionRow>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY timestampMs")
    suspend fun samplesForSession(id: Long): List<Sample>

    @Query("DELETE FROM samples WHERE sessionId = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id")
    suspend fun countForSession(id: Long): Int
}
```

`AppDb.kt`:
```kotlin
package me.jagajaga.signalmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Sample::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): SampleDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "signalmap.db"
            ).build().also { instance = it }
        }
    }
}
```

`Exporter.kt`:
```kotlin
package me.jagajaga.signalmap.data

object Exporter {
    fun csv(samples: List<Sample>): String = buildString {
        appendLine("sessionId,simSlot,timestampMs,lat,lon,accuracyM,dbm,networkType,flagged")
        for (s in samples) {
            appendLine("${s.sessionId},${s.simSlot},${s.timestampMs},${s.lat},${s.lon},${s.accuracyM},${s.dbm},${s.networkType},${s.flagged}")
        }
    }

    fun geoJson(samples: List<Sample>): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        samples.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${s.lon},${s.lat}]},""" +
                """"properties":{"sim":${s.simSlot},"dbm":${s.dbm},"networkType":"${s.networkType}",""" +
                """"accuracyM":${s.accuracyM},"timestampMs":${s.timestampMs}}}"""
            )
        }
        append("]}")
    }
}
```

- [ ] **Step 4: Run — expect PASS**: `./gradlew test --tests '*ExporterTest*'` then full `./gradlew test assembleRelease` (verifies Room/KSP compiles).

- [ ] **Step 5: Commit**: `git commit -m "feat(data): add room samples store and csv/geojson exporter"`

---

### Task 6: RecordingService

**Files:**
- Create: `app/src/main/java/me/jagajaga/signalmap/collect/RecordingService.kt`, `app/src/main/java/me/jagajaga/signalmap/collect/SignalReader.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register service)
- Test: `app/src/test/java/me/jagajaga/signalmap/collect/SignalReaderTest.kt` (pure part only)

**Interfaces:**
- Consumes: `AppDb`, `Sample`, `Mercator`.
- Produces: `RecordingService` with `companion object { val running: AtomicBoolean; @Volatile var currentSessionId: Long; @Volatile var sampleCount: Int; fun start(ctx: Context); fun stop(ctx: Context) }`; `SignalReader.pickDbm(strengths: List<Pair<Int, String>>): Pair<Int, String>?` (pure selection helper) and `SignalReader.read(tm: TelephonyManager): Pair<Int, String>?` (Android side).

- [ ] **Step 1: Failing test for the pure picker**

```kotlin
package me.jagajaga.signalmap.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalReaderTest {
    @Test fun picksFirstValidPreferringOrder() {
        // list is already ordered NR > LTE > other by read(); picker takes first valid
        assertEquals(-88 to "LTE", SignalReader.pickDbm(listOf(2147483647 to "NR", -88 to "LTE")))
    }
    @Test fun rejectsSentinelAndPositive() {
        assertNull(SignalReader.pickDbm(listOf(2147483647 to "NR", 99 to "GSM")))
    }
    @Test fun emptyIsNull() {
        assertNull(SignalReader.pickDbm(emptyList()))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**: `./gradlew test --tests '*SignalReaderTest*'`

- [ ] **Step 3: Implement**

`SignalReader.kt`:
```kotlin
package me.jagajaga.signalmap.collect

import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager

object SignalReader {
    /** First entry with a plausible negative dBm (list pre-ordered by preference). Pure; unit-tested. */
    fun pickDbm(strengths: List<Pair<Int, String>>): Pair<Int, String>? =
        strengths.firstOrNull { (dbm, _) -> dbm in -160..-20 }

    /** Reads the system-cached signal for this (per-subscription) TelephonyManager. */
    fun read(tm: TelephonyManager): Pair<Int, String>? {
        val ss = tm.signalStrength ?: return null
        val ordered = ss.cellSignalStrengths.sortedBy { cs ->
            when (cs) {
                is CellSignalStrengthNr -> 0
                is CellSignalStrengthLte -> 1
                is CellSignalStrengthWcdma -> 2
                is CellSignalStrengthGsm -> 3
                else -> 4
            }
        }.map { cs ->
            when (cs) {
                is CellSignalStrengthNr -> cs.ssRsrp to "NR"
                is CellSignalStrengthLte -> cs.rsrp to "LTE"
                is CellSignalStrengthWcdma -> cs.dbm to "WCDMA"
                is CellSignalStrengthGsm -> cs.dbm to "GSM"
                else -> cs.dbm to "OTHER"
            }
        }
        return pickDbm(ordered)
    }
}
```

`RecordingService.kt`:
```kotlin
package me.jagajaga.signalmap.collect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.Sample
import me.jagajaga.signalmap.render.Mercator
import me.jagajaga.signalmap.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "recording"
        const val NOTIF_ID = 1
        val running = AtomicBoolean(false)
        @Volatile var currentSessionId: Long = 0
        @Volatile var sampleCount: Int = 0

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tms: List<Pair<Int, TelephonyManager>> = emptyList() // simSlot -> per-sub TM

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running.getAndSet(true)) startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        currentSessionId = System.currentTimeMillis()
        sampleCount = 0
        createChannel()
        startForeground(
            NOTIF_ID, buildNotification("Recording…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        val subMgr = getSystemService(SubscriptionManager::class.java)
        val baseTm = getSystemService(TelephonyManager::class.java)
        tms = (subMgr.activeSubscriptionInfoList ?: emptyList()).map { info ->
            info.simSlotIndex to baseTm.createForSubscriptionId(info.subscriptionId)
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun onFix(loc: Location) {
        val flagged = if (loc.accuracy > 25f) 1 else 0
        val rows = tms.mapNotNull { (slot, tm) ->
            val (dbm, net) = SignalReader.read(tm) ?: return@mapNotNull null
            Sample(
                sessionId = currentSessionId, simSlot = slot,
                timestampMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                accuracyM = loc.accuracy, dbm = dbm, networkType = net,
                mx = Mercator.lonToX(loc.longitude), my = Mercator.latToY(loc.latitude),
                flagged = flagged
            )
        }
        if (rows.isEmpty()) return
        scope.launch {
            AppDb.get(this@RecordingService).dao().insertAll(rows)
            sampleCount += rows.size
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("Recording… $sampleCount samples"))
        }
    }

    private fun stopRecording() {
        fused.removeLocationUpdates(callback)
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Signal Map")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRecording()
        super.onTaskRemoved(rootIntent)
    }
}
```

Manifest addition inside `<application>`:
```xml
<service
    android:name=".collect.RecordingService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

- [ ] **Step 4: Run — expect PASS**: `./gradlew test assembleRelease`

- [ ] **Step 5: Commit**: `git commit -m "feat(collect): add foreground recording service for dual-sim sampling"`

---

### Task 7: HeatOverlay + map UI

**Files:**
- Create: `app/src/main/java/me/jagajaga/signalmap/render/HeatOverlay.kt`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/drawable/legend_gradient.xml`
- Modify: `app/src/main/java/me/jagajaga/signalmap/ui/MainActivity.kt` (replace placeholder)

**Interfaces:**
- Consumes: `SampleDao.aggregate`, `Mercator`, `HeatField`, `ColorMap`, `RecordingService.{start,stop,running,currentSessionId}`.
- Produces: `HeatOverlay(dao, scope)` osmdroid `Overlay` with `var simSlot: Int` and `fun attach(map: MapView)`, `fun requestRender()`.

- [ ] **Step 1: Implement HeatOverlay**

```kotlin
package me.jagajaga.signalmap.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.signalmap.data.SampleDao
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference
import kotlin.math.max

class HeatOverlay(
    private val dao: SampleDao,
    private val scope: CoroutineScope
) : Overlay() {
    var simSlot: Int = 0
        set(value) { field = value; requestRender() }

    private var mapRef = WeakReference<MapView>(null)
    private var bitmap: Bitmap? = null
    private var covN = 0.0; private var covS = 0.0; private var covE = 0.0; private var covW = 0.0
    private var job: Job? = null
    private val paint = Paint().apply { isFilterBitmap = true; alpha = 150 }
    private val DOWN = 8      // bitmap at 1/8 view resolution
    private val MARGIN = 1.3  // render 30% beyond the viewport

    fun attach(map: MapView) {
        mapRef = WeakReference(map)
        map.overlays.add(this)
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { requestRender(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { requestRender(); return false }
        }, 250))
        requestRender()
    }

    fun requestRender() {
        val map = mapRef.get() ?: return
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            delay(50)
            renderOnce(map)
        }
    }

    private suspend fun renderOnce(map: MapView) {
        data class View(val n: Double, val s: Double, val e: Double, val w: Double,
                        val zoom: Double, val pw: Int, val ph: Int)
        val v = withContext(Dispatchers.Main) {
            val bb = map.boundingBox.increaseByScale(MARGIN.toFloat())
            View(bb.latNorth, bb.latSouth, bb.lonEast, bb.lonWest,
                 map.zoomLevelDouble, map.width, map.height)
        }
        if (v.pw == 0 || v.ph == 0) return

        val shift = Mercator.shiftForZoom(v.zoom)
        val x0 = Mercator.lonToX(v.w); val x1 = Mercator.lonToX(v.e)
        val y0 = Mercator.latToY(v.n); val y1 = Mercator.latToY(v.s) // y grows southward
        if (x1 <= x0 || y1 <= y0) return
        val cells = dao.aggregate(simSlot, shift, x0, x1, y0, y1)

        val bw = max(8, (v.pw * MARGIN / DOWN).toInt())
        val bh = max(8, (v.ph * MARGIN / DOWN).toInt())
        val field = HeatField(bw, bh)
        val cellUnits = (1L shl shift).toFloat()
        val sx = bw.toFloat() / (x1 - x0)
        val sy = bh.toFloat() / (y1 - y0)
        val radius = max(2f, cellUnits * sx * 2f)
        for (c in cells) {
            val centerX = (c.cx.toLong() shl shift) + (1L shl (shift - 1))
            val centerY = (c.cy.toLong() shl shift) + (1L shl (shift - 1))
            val px = (centerX - x0) * sx
            val py = (centerY - y0) * sy
            field.splat(px, py, ColorMap.norm(c.maxDbm), radius)
        }
        val pixels = field.colorize()
        val bmp = Bitmap.createBitmap(pixels, bw, bh, Bitmap.Config.ARGB_8888)

        withContext(Dispatchers.Main) {
            bitmap = bmp
            covN = v.n; covS = v.s; covE = v.e; covW = v.w
            map.invalidate()
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val bmp = bitmap ?: return
        val proj = mapView.projection
        val tl = proj.toPixels(GeoPoint(covN, covW), null)
        val br = proj.toPixels(GeoPoint(covS, covE), null)
        canvas.drawBitmap(bmp, null, Rect(tl.x, tl.y, br.x, br.y), paint)
    }
}
```

- [ ] **Step 2: Layout and legend**

`app/src/main/res/drawable/legend_gradient.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:type="linear"
        android:angle="0"
        android:startColor="#FFF44336"
        android:centerColor="#FFFFEB3B"
        android:endColor="#FF4CAF50" />
    <corners android:radius="4dp" />
</shape>
```

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <org.osmdroid.views.MapView
        android:id="@+id/map"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <com.google.android.material.button.MaterialButtonToggleGroup
        android:id="@+id/simToggle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal"
        android:layout_marginTop="40dp"
        app:singleSelection="true"
        app:selectionRequired="true">
        <Button
            android:id="@+id/btnSim1"
            style="@style/Widget.Material3.Button.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="SIM 1" />
        <Button
            android:id="@+id/btnSim2"
            style="@style/Widget.Material3.Button.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="SIM 2" />
    </com.google.android.material.button.MaterialButtonToggleGroup>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp">
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="-120"
            android:textSize="12sp" />
        <View
            android:layout_width="0dp"
            android:layout_height="10dp"
            android:layout_weight="1"
            android:layout_marginHorizontal="8dp"
            android:background="@drawable/legend_gradient" />
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="-70 dBm"
            android:textSize="12sp" />
    </LinearLayout>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabFollow"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|start"
        android:layout_margin="16dp"
        android:layout_marginBottom="72dp"
        android:src="@android:drawable/ic_menu_mylocation"
        app:fabSize="mini" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabRecord"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:layout_marginBottom="72dp"
        android:src="@android:drawable/ic_media_play" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabSessions"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|end"
        android:layout_margin="16dp"
        android:layout_marginTop="40dp"
        android:src="@android:drawable/ic_menu_sort_by_size"
        app:fabSize="mini" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 3: MainActivity**

```kotlin
package me.jagajaga.signalmap.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.collect.RecordingService
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.render.HeatOverlay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var heat: HeatOverlay
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var fabRecord: FloatingActionButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                grants[Manifest.permission.READ_PHONE_STATE] == true
            ) startRecording()
            else Toast.makeText(
                this, "Location and phone permissions are required to record", Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(52.52, 13.405))

        heat = HeatOverlay(AppDb.get(this).dao(), lifecycleScope)
        heat.attach(map)

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        myLocation.enableMyLocation()
        map.overlays.add(myLocation)
        myLocation.runOnFirstFix {
            runOnUiThread {
                myLocation.myLocation?.let { map.controller.animateTo(it) }
            }
        }

        findViewById<MaterialButtonToggleGroup>(R.id.simToggle).apply {
            check(R.id.btnSim1)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) heat.simSlot = if (checkedId == R.id.btnSim2) 1 else 0
            }
        }

        fabRecord = findViewById(R.id.fabRecord)
        fabRecord.setOnClickListener {
            if (RecordingService.running.get()) {
                RecordingService.stop(this)
                updateRecordIcon()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            }
        }

        findViewById<FloatingActionButton>(R.id.fabFollow).setOnClickListener {
            if (myLocation.isFollowLocationEnabled) myLocation.disableFollowLocation()
            else myLocation.enableFollowLocation()
        }

        findViewById<FloatingActionButton>(R.id.fabSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }

        // periodic refresh while recording
        lifecycleScope.launch {
            while (true) {
                delay(3000)
                if (RecordingService.running.get()) heat.requestRender()
                updateRecordIcon()
            }
        }
    }

    private fun startRecording() {
        RecordingService.start(this)
        updateRecordIcon()
    }

    private fun updateRecordIcon() {
        fabRecord.setImageResource(
            if (RecordingService.running.get()) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
```

Note: `SessionsActivity` referenced here is created in Task 8 — create an empty registered `SessionsActivity` stub in this task so the build stays green:

```kotlin
package me.jagajaga.signalmap.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SessionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
}
```
Manifest addition inside `<application>`:
```xml
<activity android:name=".ui.SessionsActivity" android:exported="false" />
```

- [ ] **Step 4: Build**: `./gradlew test assembleRelease` — expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**: `git commit -m "feat(ui): add map screen with smooth signal gradient overlay"`

---

### Task 8: Sessions + export UI

**Files:**
- Create: `app/src/main/res/layout/activity_sessions.xml`, `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/java/me/jagajaga/signalmap/ui/SessionsActivity.kt` (replace stub), `app/src/main/AndroidManifest.xml` (FileProvider)

**Interfaces:**
- Consumes: `SampleDao.{sessions, samplesForSession, deleteSession}`, `Exporter`.

- [ ] **Step 1: FileProvider config**

`app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

Manifest addition inside `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="me.jagajaga.signalmap.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 2: Layout**

`app/src/main/res/layout/activity_sessions.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ListView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/sessionList"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="8dp" />
```

- [ ] **Step 3: SessionsActivity**

```kotlin
package me.jagajaga.signalmap.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.Exporter
import me.jagajaga.signalmap.data.SessionRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsActivity : AppCompatActivity() {
    private var rows: List<SessionRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        val list = findViewById<ListView>(R.id.sessionList)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        lifecycleScope.launch {
            rows = AppDb.get(this@SessionsActivity).dao().sessions()
            val labels = rows.map { r ->
                val mins = (r.endMs - r.startMs) / 60000
                "${fmt.format(Date(r.startMs))}  ·  ${mins} min  ·  ${r.n} samples"
            }
            list.adapter = ArrayAdapter(
                this@SessionsActivity, android.R.layout.simple_list_item_1, labels
            )
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this)
                .setTitle(fmt.format(Date(r.startMs)))
                .setItems(arrayOf("Export CSV", "Export GeoJSON", "Delete")) { _, which ->
                    when (which) {
                        0 -> export(r.sessionId, "csv", "text/csv")
                        1 -> export(r.sessionId, "geojson", "application/geo+json")
                        2 -> delete(r.sessionId)
                    }
                }
                .show()
        }
    }

    private fun export(sessionId: Long, ext: String, mime: String) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val samples = AppDb.get(this@SessionsActivity).dao().samplesForSession(sessionId)
                val content = if (ext == "csv") Exporter.csv(samples) else Exporter.geoJson(samples)
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                File(dir, "session-$sessionId.$ext").apply { writeText(content) }
            }
            val uri = FileProvider.getUriForFile(
                this@SessionsActivity, "me.jagajaga.signalmap.files", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Export session"
                )
            )
        }
    }

    private fun delete(sessionId: Long) {
        lifecycleScope.launch {
            AppDb.get(this@SessionsActivity).dao().deleteSession(sessionId)
            recreate()
        }
    }
}
```

- [ ] **Step 4: Build**: `./gradlew test assembleRelease` — expect `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**: `git commit -m "feat(ui): add sessions list with csv/geojson export and delete"`

---

### Task 9: README, push, release v0.1.0

**Files:**
- Create: `README.md`

- [ ] **Step 1: README**

```markdown
# Signal Map

Walk around, record cellular signal strength for both SIMs, and see a smooth
red→green gradient of the best-signal spots on OpenStreetMap — at any zoom.

## Install

1. Go to [Releases](https://github.com/jagajaga/cellular-map/releases) and
   download `signalmap.apk` (or grab the `signalmap-apk` artifact from any
   [Actions](https://github.com/jagajaga/cellular-map/actions) run).
2. Open it on your phone (allow "install unknown apps" for your browser/file
   manager the first time).
3. **Updates install in place** — every APK is signed with the same key and
   has an increasing version, so you never need to uninstall first.

## Use

- Tap ▶ to record (grant location + phone permissions). Walk around.
- SIM 1 / SIM 2 buttons switch which SIM's map you see.
- Legend: red = −120 dBm, green = −70 dBm. No color = no data.
- Zoom in for meter-scale spots; zoom out for area overview (cells aggregate
  by max, so a good spot stays green).
- ☰ sessions button: export any session as CSV/GeoJSON or delete it.

## Notes

- GPS accuracy is ~3–5 m; samples worse than 25 m are excluded from the map.
- The signing keystore is committed (password `signalmap`) purely for
  install-continuity of a hobby app — do not reuse it for anything serious.
- Requires Android 10+.

## Build

CI builds on every push (`.github/workflows/android.yml`). Locally:
`./gradlew assembleRelease` with JDK 17 and an Android SDK.
```

- [ ] **Step 2: Push, verify CI green**

```bash
git add -A && git commit -m "docs(readme): add install and usage guide"
git push
gh run watch --exit-status $(gh run list -L1 --json databaseId -q '.[0].databaseId')
```

- [ ] **Step 3: Tag release and verify APK attached**

```bash
git tag v0.1.0 && git push origin v0.1.0
gh run watch --exit-status $(gh run list -L1 --json databaseId -q '.[0].databaseId')
gh release view v0.1.0 --json assets -q '.assets[].name'
```
Expected: `signalmap.apk`.

- [ ] **Step 4: Signature sanity check (reinstall guarantee)**

Download the release APK and confirm it is signed with the committed key:
```bash
gh release download v0.1.0 -p signalmap.apk -D /tmp/apkcheck --clobber
~/toolchain/android-sdk/build-tools/35.0.0/apksigner verify --print-certs /tmp/apkcheck/signalmap.apk
keytool -list -keystore app/signalmap.keystore -storepass signalmap -alias signalmap
```
Expected: certificate SHA-256 digests match between the APK and the keystore.

---

## Self-review notes

- Spec coverage: collection (T6), SIM toggle + metric + gradient + zoom adaptivity (T4/T7), resolution floor (T3 `shiftForZoom`), flagged>25m exclusion (T6 write, T5 query), export CSV/GeoJSON (T5/T8), sessions list/delete (T8), CI + release (T2/T9), stable signing/reinstall (T1/T2/T9), permissions & error paths (T6/T7), tests for all pure units (T3/T4/T5/T6).
- Known deferred manual verification: dual-SIM readings, overlay visuals, and recording lifecycle need a real device (spec's "instrumented/manual" section) — user does this after installing the release.
