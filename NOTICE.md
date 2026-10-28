# Third-party notices

OpenCode Mobile is an unofficial client. It bundles small runtime components in the
APK and downloads Debian and opencode at runtime.

## Bundled in the APK

### Termux terminal-emulator and terminal-view

- Files: `app/src/main/java/com/termux/**`,
  `app/src/main/jniLibs/arm64-v8a/libtermux.so`,
  `app/src/main/res/drawable/text_select_handle_*.xml`
- Source: https://github.com/termux/termux-app (v0.118.3 terminal sources)
- License: **GNU General Public License v3.0** (see `LICENSE`)
- Notes: sources were vendored, AndroidX annotations removed, and
  `libtermux.so` was extracted from the official arm64 APK and had a spurious
  `libstdc++.so` dependency removed with `patchelf`.

### PRoot

- Files: `app/src/main/assets/bootstrap/proot`, `proot-loader`, `proot-loader32`
- Version: 5.1.107.101 (Termux build)
- Source: https://github.com/termux/proot
- License: **GNU General Public License v2.0**
- Notes: bundled binary has an RPATH of `$ORIGIN/../lib`. The Termux build uses an
  unbundled ELF loader, so `loader`/`loader32` are shipped alongside it and passed to
  proot through `PROOT_LOADER`/`PROOT_LOADER_32`.

### talloc

- File: `app/src/main/assets/bootstrap/libtalloc.so.2`
- Source: https://talloc.samba.org/ (Termux build)
- License: **GNU Lesser General Public License v3.0** (or later)

### libandroid-shmem

- File: `app/src/main/assets/bootstrap/libandroid-shmem.so`
- Source: https://github.com/termux/libandroid-shmem
- License: **GNU General Public License v2.0**

### BusyBox

- File: `app/src/main/assets/bootstrap/busybox`
- Version: 1.36.1.1 (static, topjohnwu build)
- Source: https://busybox.net/ and https://github.com/topjohnwu/libsu
- License: **GNU General Public License v2.0**

### Application code

The original OpenCode Mobile code (`dev.opencodemobile.*`, build scripts and
resources) is released under the **GNU General Public License v3.0** as a whole,
because it is linked with the GPLv3 Termux terminal sources. See `LICENSE`.

## Downloaded at runtime (not bundled)

### Debian GNU/Linux 13 "trixie" arm64 root filesystem

- Source: https://images.linuxcontainers.org/images/debian/trixie/arm64/default/
- License: Debian Social Contract / various free software licenses.
- The image is downloaded and extracted entirely on the device.

### opencode

- Source: https://github.com/anomalyco/opencode/releases
- Binary: `opencode-linux-arm64.tar.gz`
- License: see the upstream repository (`LICENSE` in the release/source).
- opencode is a project by Anomaly. OpenCode Mobile is not affiliated with or
  endorsed by it.

## Android SDK / build-time only

- `android-34.jar` (Android SDK, Apache 2.0) and R8/D8 are used only to build the
  APK and are not redistributed in it.
- Debian's `aapt2`, `zipalign` and `apksigner` are build-time tools.

## Full license texts

- GPLv3: `LICENSE` in this repository.
- GPLv2: https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
- LGPLv3: https://www.gnu.org/licenses/lgpl-3.0.txt

If you redistribute an APK built from this repository, keep this notice, the
`LICENSE` file and the corresponding source available as required by the GPL.
