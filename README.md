# InkPress: Web-to-EPUB Android Converter (Optimized for E-Ink)

**InkPress** is a minimalist, distraction-free Android application designed to parse webpage articles and package them into highly compatible, image-free EPUB documents. It is tailored specifically for e-ink e-readers (such as the **Xteink X3**, Onyx Boox, Kindle, and Kobo) that have limited rendering engines, slow refresh rates, or offline indexing issues.

---

## ✨ Features

* **Distraction-Free Text Only**: Strips out all `<img>`, `<script>`, `<style>`, `<iframe>`, and layout trackers, keeping only clean, readable XHTML text.
* **Strict EPUB 2.0.1 Compliance**: 
  * Writes the mandatory `mimetype` entry first and uncompressed (`ZipEntry.STORED`).
  * Establishes correct case-sensitive DOCTYPE formats.
  * Formats all template files starting at Line 1 Column 1, eliminating leading newlines that cause e-ink database indexers to hang or crash.
* **Seamless Share-Sheet Interception**: Intercepts webpage URLs shared directly from browsers like Google Chrome.
* **E-Ink Custom UI**: Features a sleek paper-style light/dark dashboard built with Jetpack Compose.
* **Local Storage Integration**: Saves EPUBs directly to the device's public `Downloads/InkPress` directory.

---

## 📂 Project Structure

```text
├── settings.gradle.kts      # Project dependency settings
├── build.gradle.kts         # Root Gradle build file
├── gradle.properties        # Project-wide properties
├── PRIVACY_POLICY.md        # Privacy policy template for Play Store compliance
├── README.md                # Project documentation (this file)
└── app/
    ├── build.gradle.kts     # App-level dependencies (Compose, Jsoup)
    ├── proguard-rules.pro   # ProGuard/R8 shrinking & keep rules
    └── src/
        └── main/
            ├── AndroidManifest.xml  # Requests Internet and handles share intents
            ├── java/com/inkpress/app/
            │   ├── MainActivity.kt  # Compose UI & share sheet controller
            │   ├── Scraper.kt       # Jsoup HTML downloader and text sanitizer
            │   ├── EpubBuilder.kt   # Strict XML-to-EPUB zip package compiler
            │   └── StorageHelper.kt # MediaStore downloads directory exporter
            └── res/
                └── drawable/        # Vector adaptive launcher icons
```

---

## 🛠️ How to Build and Run

### 1. Requirements
* JDK 17 or higher
* Android SDK (API Level 34)

### 2. Local Compilation
To compile the debug version of the app:
```bash
./gradlew assembleDebug
```
The output APK is generated at:  
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Deploy to Test Device
Ensure USB Debugging is enabled on your phone/emulator and run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 Google Play Store Releases

To compile the production Android App Bundle (`.aab`):
1. Add your signing keystore details to your local `gradle.properties` file:
   ```properties
   inkpress.release.keystorePath=/path/to/inkpress.keystore
   inkpress.release.storePassword=keystore_password
   inkpress.release.keyAlias=inkpress-key-alias
   inkpress.release.keyPassword=key_password
   ```
2. Build the optimized App Bundle:
   ```bash
   ./gradlew bundleRelease
   ```
The output signed App Bundle is saved to:  
`app/build/outputs/bundle/release/app-release.aab` *(Minified size: **~2.65 MB**)*.

---

## 📄 License
This project is open-source and licensed under the MIT License.
