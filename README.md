# Open FS (Open Lock File System)

**Open FS** is a banking-grade, highly secure, zero-trust encrypted file system application for Android built with Kotlin and Jetpack Compose. Designed with a **Dual-Lock Security Architecture**, Open FS ensures that your sensitive documents, images, videos, and private files remain encrypted at rest with AES-256-GCM and never leak unencrypted data to device storage or RAM.

---

## 🛡️ Key Features & Technical Highlights

### 1. Dual-Lock Security Architecture
- **App Lock (UI Layer):** Secures the application UI using a custom PIN or device Biometric authentication (Fingerprint / Face Unlock).
- **Vault Container Security (Storage Layer):** Independent encrypted vault files (`<VaultName>.ofs`), each protected by a master password using PBKDF2 (10,000 iterations) with salted AES-256 key derivation.
- **RAM-Only Key Derivation:** Zero secret keys, plain-text passwords, or master keys are ever written to disk/ROM.
- **Auto-Lock Protection:** Automatically locks active vault sessions on app backgrounding (`ON_STOP`), screen-off events (`ACTION_SCREEN_OFF`), or after configurable inactivity timeouts (1, 5, 15, or 30 minutes).

### 2. Banking-Grade Security & Anti-Tamper Engine
- **Debugger & Dynamic Analysis Detection:** Detects attached debuggers (Ghidra, IDA Pro, Frida, JDB) and continuously inspects memory maps (`/proc/self/maps`) and local TCP ports for Frida / Xposed / Substrate hooks.
- **Root & Magisk Detection:** Scans for Superuser binaries, Magisk, KernelSU, BusyBox, and custom test-keys ROMs (with compatibility handling for Android 12).
- **Emulator & Virtualization Detection:** Identifies generic Android emulators, Genymotion, and virtualized test environments.
- **Self-Destruct Data Wipe:** Progressive exponential lockout delays on repeated failed authentication attempts, culminating in an automatic, irreversible full data wipe after 12 consecutive failed attempts.
- **Scrambled Keypad:** Randomly shuffles the layout of the PIN digit keypad on every session/retry to prevent shoulder surfing and touch pattern tracking.
- **FLAG_SECURE Enforcement:** Suppresses screenshots, screen recordings, and app switcher recents thumbnail previews system-wide.
- **RAM Memory Zeroization:** Overwrites sensitive key byte arrays and character buffers with zeros immediately after cryptographic operations.

### 3. High-Performance Streaming Cryptography (`ChunkedCipherStream`)
- **AES-256-GCM Authenticated Encryption:** Protects file data confidentiality and tamper resistance using AES in Galois/Counter Mode with 128-bit authentication tags.
- **64 KB Chunk Streaming:** Encrypts and decrypts large files in 64 KB chunks, allowing seamless handling of multi-gigabyte files with minimal RAM footprint.
- **Per-Chunk IV Derivation & Counter Integrity:** Derives unique IVs per chunk to prevent payload reordering, chunk deletion, or partial ciphertext modification.

### 4. Multi-Vault Container Management
- **Named Single-File Containers (`.ofs`):** Create, manage, and switch between multiple independent encrypted container files.
- **Accessible Storage Auto-Sync:** Integrates with Android Storage Access Framework (SAF) to automatically sync container state to external storage, cloud drives, or PC-accessible locations.
- **Vault Export & Backup:** Export container backups (`.ofs` / `.bak`) and restore them seamlessly with password verification and SHA-256 hash validation.

### 5. Secure File Explorer & Management
- **Hierarchical Directory Tree:** Full support for nested sub-folders, breadcrumb path navigation, file creation, item renaming, and recursive folder deletion.
- **Universal SAF Import:** Import files of any format (documents, images, videos, audio, PDFs, archives) via standard Android document pickers.
- **Search & Pull-to-Refresh:** Real-time in-vault item search and pull-to-refresh container synchronization.

### 6. Ephemeral In-RAM Media & Document Previewer
- **Zero-Disk-Footprint Viewing:** Preview images, videos, audio, and text documents decrypted on-demand directly in RAM using `RamDecryptedMediaDataSource`. No unencrypted bytes ever touch storage.
- **Built-in Media Players:** Integrated video player (via SurfaceView/MediaPlayer) and audio player with playback controls and position sliders.
- **Instant Memory Eviction:** Decrypted bitmaps, media data sources, and temporary preview buffers are immediately recycled and evicted from RAM upon screen exit or vault lock.

### 7. Modern Jetpack Compose UI & Customization
- **Material 3 UI:** Built natively with Jetpack Compose, offering clean dark/light themes and edge-to-edge display integration.
- **Vault Statistics & Settings:** View active vault statistics (file count, total container size), manage PIN/Master Password changes, toggle Biometrics, and adjust auto-lock duration.

---

## 📱 Tech Stack & Dependencies

- **Language:** Kotlin 2.x
- **UI Framework:** Jetpack Compose (Material 3, Navigation Compose)
- **Architecture:** MVVM with StateFlow & Coroutines
- **Database:** Room DB (SQLite) for encrypted container metadata
- **Security & Crypto:** AndroidX Security-Crypto (EncryptedSharedPreferences), AndroidX Biometric, Java Cryptography Architecture (JCA)
- **Storage:** Storage Access Framework (SAF) & Custom Zip Single-File Container Format

---

## 🚀 Building & Running

1. **Clone the Repository:**
   ```bash
   git clone https://github.com/your-org/open-fs-app.git
   cd open-fs-app
   ```
2. **Open in Android Studio:** Open the project directory in Android Studio Ladybug (or newer).
3. **Build the Project:** Run `./gradlew assembleDebug` or click **Build > Make Project** in Android Studio.
4. **Deploy:** Deploy the debug build to an Android device running Android 12 (API level 31) or higher.

---

## 🔒 Security Notice

Open FS is designed with strict zero-trust security principles. Please note:
- **Forgotten Passwords/PINs:** There is no master password recovery key or backdoor. If you forget your Master Password, your vault container cannot be decrypted.
- **Failed Attempts Wipe:** 12 consecutive failed attempts will wipe all local vault containers and application state. Always maintain exported `.ofs` backups in a safe secondary location.
