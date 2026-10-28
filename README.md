# OpenCode Mobile

A native Android app that puts a real shell, a Debian userland and the complete
[opencode](https://opencode.ai) AI coding agent in your pocket — no root, no PC, no
Termux required.

```
┌──────────────────────────────────────────┐
│  OPENCODE      >_ TERM   WEB   ⋮         │
├──────────────────────────────────────────┤
│  root@opencode:/# opencode               │
│  ...full TUI...                          │
├──────────────────────────────────────────┤
│ ESC TAB CTRL ALT SHIFT ← ↑ ↓ → PASTE ⌨  │
└──────────────────────────────────────────┘
```

## What you get

- **Real terminal** — xterm-256color emulation based on the Termux terminal-emulator,
  with a full-screen TUI, colour, mouse reporting, scrollback, copy/paste and an
  on-screen modifier/arrow-key row.
- **Debian 13 (trixie), arm64** — a genuine LXC root filesystem run through PRoot.
  `apt`, `bash`, `git`, `curl`, `ssh`, `rg`, `jq`, `nano`, `less` and friends are
  provisioned on first run.
- **Full opencode** — the official linux-arm64 release, so the TUI, the
  `--mini` interface, the web UI, `opencode auth login`, providers/models, MCP,
  LSP, sessions, plugins, `stats`, `github`, ACP and the rest of the CLI are all
  available.
- **Web UI tab** — runs `opencode serve` on `127.0.0.1:4096` and shows its
  mobile-friendly web interface in a WebView.
- **Sessions survive app switching** — a foreground service keeps shells, long AI
  tasks and installs alive when you leave the app.

## Requirements

| | |
|---|---|
| Device | 64-bit ARM (`arm64-v8a`) only |
| Android | 8.0 Oreo (API 26) or newer |
| Storage | ~2–3 GB free for Debian + packages + opencode |
| Network | Required for the first install and for AI providers |
| Root | **Not required** |

> **Page size note:** the bundled PRoot/busybox binaries and `libtermux.so` are built
> for 4 KB pages. Devices shipping a 16 KB-page kernel (some Android 15/16 flagships)
> may refuse to load them. 4 KB-page devices are the supported configuration.

## Install

A signed APK is produced by the build script:

```bash
adb install -r build/opencode-mobile.apk
```

Or copy `opencode-mobile.apk` to the device and open it (you may need to allow
"Install unknown apps" for your file manager).

The APK is debug-signed, so it is fine for sideloading but not for Play Store
distribution (Play also requires `targetSdk` 33+).

## First run

1. Tap **Install Debian + OpenCode**.
2. The app downloads the latest Debian trixie arm64 LXC image (~90 MB), extracts it,
   runs `apt-get update && apt-get install` for the base tools, and downloads the
   latest `opencode-linux-arm64` release (~75 MB).
3. When it finishes, sign in with **⋮ → Sign in / manage providers**
   (`opencode auth login`), then start chatting from the terminal or the web tab.

The install runs in a foreground service, so you can switch apps while it works.
Progress and a live log are shown in the setup screen. If anything goes wrong,
**⋮ → View logs** shows both the install and server logs, and
**Settings → Repair container** re-runs provisioning without re-downloading Debian.

## Using it

| Action | How |
|---|---|
| Shell | **>_ TERM** tab, or **⋮ → New terminal session** |
| Switch sessions | **⋮ → Switch session** |
| opencode TUI | **⋮ → OpenCode terminal (TUI)** |
| Small-screen TUI | **⋮ → OpenCode mini TUI (small screens)** (`opencode --mini`) |
| Web UI | **WEB** tab (starts the server automatically) |
| Sign in / providers | **⋮ → Sign in / manage providers** |
| Models / usage | **⋮ → List available models**, **Token usage statistics** |
| Update opencode | **⋮ → Update opencode** (downloads the newest release) |
| Font size | Settings slider (or volume keys in the terminal) |
| Shared storage | `Settings → Mount Android shared storage at /root/storage` |
| Logs | **⋮ → View logs** (copyable) |
| Start over | **⋮ → Reset Debian container** |

Inside the container, `/root` is the home directory and `/root/storage` (when enabled)
is your Android shared storage. Configuration lives in `~/.config/opencode/`.
The first shell shows a short README with common commands.

## Building

### Android Studio / Gradle

Open the `opencode-android` directory in Android Studio (or run Gradle 8.7+ with
JDK 17):

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

The Gradle project targets `compileSdk 34`, `minSdk 26` and `targetSdk 28`, is
Java-only, and has **no third-party runtime dependencies**.

### Manual aarch64 build (no Gradle)

The APK in `build/` is produced on an arm64 Linux host where the Android SDK's
x86_64 build tools cannot execute. It uses Debian's `aapt2`, `apksigner` and
`zipalign`, plus downloaded `android-34.jar` and R8/D8:

```bash
scripts/build.sh
```

Tool locations can be overridden with `AAPT2`, `APKSIGNER`, `ZIPALIGN`,
`ANDROID_JAR` and `R8_JAR`. See `scripts/build.sh` for details.

### Project layout

```
app/src/main/
  AndroidManifest.xml            targetSdk 28 for exec-from-app-data
  assets/bootstrap/              proot, libtalloc, libandroid-shmem, busybox
  jniLibs/arm64-v8a/libtermux.so Termux PTY/JNI implementation
  java/com/termux/               vendored terminal-emulator + terminal-view (GPLv3)
  java/dev/opencodemobile/
    App.java Paths.java Prefs.java MainActivity.java
    core/Installer.java          download, extract, apt, opencode install
    core/Proot.java              proot command/environment construction
    core/SessionManager.java     terminal sessions and Termux client callbacks
    core/SessionService.java     foreground service/notification
    core/OpencodeServer.java     `opencode serve` lifecycle
  res/                           theme, colours, strings, drawables
scripts/build.sh                 manual aarch64 APK pipeline
```

## How it works

- Executables are shipped in the APK's assets, copied to
  `/data/user/0/dev.opencodemobile/files/usr/bin` and `chmod +x`ed at first run.
- `proot -0 -r <rootfs>` provides a fake root and a Debian filesystem view. `/dev`,
  `/proc`, `/sys` and a private `/dev/shm` are bind-mounted into the guest; Android
  shared storage can be bind-mounted at `/root/storage`.
- The terminal runs `proot … /bin/bash -l` on a PTY created by Termux's JNI layer.
- The web tab runs `opencode serve --hostname 127.0.0.1 --port 4096` inside the same
  container and loads `http://127.0.0.1:4096/` in a WebView.
- `targetSdk 28` is deliberate: starting with Android 10, apps targeting API 29+
  cannot `exec` files from their private data directory under W^X/SELinux policy.
  Raising it requires a different execution strategy (e.g. an extracted native
  loader or a bundled terminal library), so it is kept at 28.

## Known limitations

- **arm64 only** — the bundled proot/busybox/opencode binaries are arm64.
- **Not Play Store distributable** — Play requires a modern `targetSdk`; sideloading
  is the intended path.
- **16 KB page kernels** may reject the 4 KB-aligned binaries (see above).
- **Filesystems without hardlink support** are handled by unpacking the rootfs through
  proot's `--link2symlink` extension (the same trick proot-distro uses). Extraction is
  a bit slower in that case because every syscall is traced.
- **PRoot is not a VM** — it shares the Android kernel. There are no kernel modules,
  no Docker, no systemd services, and some syscalls are emulated more slowly. It is
  fine for shells, Git, package management and coding agents, not for servers that
  need privileged kernel features.
- **First install needs a good connection** and a few minutes; `apt-get` output is
  logged but the progress bar is approximate.
- **Aggressive battery managers** (Xiaomi, Samsung, etc.) may still kill background
  sessions; exempt the app from battery optimisation if long tasks get interrupted.
- **Shared storage is read-mostly on Android 11+** — the app targets API 28 and uses
  legacy storage, but newer Android releases restrict writes outside the app sandbox
  without "All files access". Copy files into the container with `adb push` or use the
  in-app terminal for writes inside `/root`.
- **Browser-based sign-in** — `opencode auth login` prints a URL/device code in the
  terminal; open it in your Android browser. The guest cannot launch Android apps.
- LSP servers are not preinstalled — opencode can install language servers on demand
  as it normally does.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Install fails at download | Check connection/DNS, then **Retry installation**. The pinned Debian mirror is `images.linuxcontainers.org`. |
| `tar: can't create hardlink …` | Should be automatic now: the app detects that the filesystem refuses `link()` and re-extracts through proot `--link2symlink`. If extraction still fails, check **View logs** for `proot cannot execute programs on this device` (that is the bundled proot loader failing, not tar). |
| `apt-get` fails | **Settings → Repair container**; check **View logs** for the exact error. |
| Web tab stays on "Starting…" | Open **View logs → SERVER LOG**. Port 4096 may be in use; fully stop sessions and retry. |
| opencode cannot reach a provider | Run `opencode auth login` in the terminal and verify with `opencode models`. |
| App killed during long task | Disable battery optimisation for OpenCode Mobile and keep the notification alive. |
| Need a clean slate | **⋮ → Reset Debian container** (deletes the guest, not Android storage). |

## Third-party components and licenses

OpenCode Mobile vendors or downloads several independent projects. See
[NOTICE.md](NOTICE.md) for the full list and license texts. In short:

- **Termux terminal-emulator / terminal-view** — GPLv3. Because these sources are
  compiled into the app, OpenCode Mobile as a whole is distributed under **GPLv3**
  (see [LICENSE](LICENSE)).
- **PRoot** — GPLv2.
- **talloc** — LGPLv3.
- **busybox** — GPLv2.
- **Debian root filesystem** — various free licenses; downloaded at runtime from
  the Linux Containers image server, not bundled.
- **opencode** — downloaded at runtime from the official GitHub releases; see the
  upstream project for its license.

`opencode` is built by Anomaly. This is an unofficial client and is not affiliated
with or endorsed by the opencode project.
