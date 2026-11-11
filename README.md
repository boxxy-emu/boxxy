<p align="center">
  <img src=".github/boxxy-icon.png" width="128" alt="Boxxy icon">
</p>

<h1 align="center">Boxxy</h1>

<p align="center">
  An original Xbox emulator for Android, built on <a href="https://xemu.app">xemu</a>.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2013%2B-3ddc84">
  <img alt="ABI" src="https://img.shields.io/badge/ABI-arm64--v8a-6cf04a">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--2.0-1fdb5c">
  <img alt="Status" src="https://img.shields.io/badge/status-work%20in%20progress-d2ff3c">
</p>

---

Boxxy is a port of [xemu](https://github.com/xemu-project/xemu) to Android on
ARM64. It keeps xemu's emulation of the original Xbox hardware: the x86 CPU, the
NV2A GPU, the MCPX audio and the rest. Around that it adds an Android app built
for handhelds, with a game library, controller support, save states and
dual-screen devices.

xemu runs the x86 CPU through QEMU's translator. On an x86 PC that is x86 code
running on x86, but on a phone every instruction is translated to ARM. Most of
the work in this fork goes into making that fast and cool-running enough to play
on a handheld. See [Performance](#performance).

> [!NOTE]
> Boxxy is an independent project and is not affiliated with the xemu team.
> Please report bugs in this port **here**, not to xemu.

## Status

Boxxy is in active development. It is developed and tested on an **AYN Thor**
(Snapdragon 8 Gen 2, dual screen). Other devices should work if they meet the
requirements below, but they are untested.

Boxxy uses xemu's emulation core, so game compatibility should generally follow
xemu's [compatibility list](https://xemu.app/#compatibility). Many games have
not been tested on Boxxy yet, though, and speeds on Android will be lower than
on a desktop PC.

## Features

**Library**
- Cover art, hero backgrounds and logos from
  [SteamGridDB](https://www.steamgriddb.com/) (needs your own free API key),
  fetched automatically as games are added.
- Showcase and grid views, a favourite game pinned to the front, and full
  controller navigation.
- Reads titles and title IDs straight from XISO images.
- Launch games from [ES-DE](https://es-de.org/): see
  [`android/tools/es-de`](android/tools/es-de).

**Playing**
- Vulkan renderer, with OpenGL ES as a fallback. Custom Vulkan drivers such as
  Mesa Turnip can be loaded from a ZIP.
- Shaders compile in the background, so new effects don't freeze the game.
- Physical controllers with button remapping, plus an on-screen touch overlay
  that hides when a controller connects.
- 16:9 or 4:3 output, a per-game widescreen setting, brightness and the game's
  own gamma ramp.
- An optional performance overlay with frame-time graph.

**Saves**
- Save slots with thumbnails.
- Quick Resume: leaving a game saves it, and the next launch picks up where you
  left off.
- Fast save states: the game pauses for about 0.1 s per save.

**Settings and devices**
- Per-game settings, keyed by title ID, with built-in defaults for titles that
  need them.
- Dual-screen devices: the library and the in-game menu use the second screen.
- Frame pacing locked to the panel's real refresh rate, for judder-free output.

## Requirements

- Android 13 or later, on an ARM64 (`arm64-v8a`) device.
- A recent high-end SoC. The x86 CPU is emulated in software, so single-core
  speed matters most.
- Vulkan support is recommended.
- **Your own Xbox system files**, the same ones xemu needs: an MCPX boot ROM, a
  flash BIOS and a hard-disk image. Boxxy does not include them. xemu's
  [required files guide](https://xemu.app/docs/required-files/) explains what
  each one is.
- Games as XISO images, dumped from discs you own.

## Getting started

There are no release builds yet; for now Boxxy has to be
[built from source](#building).

1. Install the APK.
2. On first launch, the setup screen asks for the MCPX boot ROM, the BIOS and
   the hard-disk image.
3. Add a folder of game images to the library.
4. Optionally, enter a SteamGridDB API key under Settings for artwork.

## Building

Boxxy builds in two stages:

1. **Meson/Ninja** cross-compiles the xemu/QEMU core for `arm64-android` into
   static libraries.
2. **Gradle/CMake** compiles the Android-specific sources and the JNI layer,
   links them against the core, and packages the APK.

You will need:
- The Android SDK, NDK 29.0.14206865 and CMake 3.22.1, and JDK 17 or newer.
- A clone of [vcpkg](https://github.com/microsoft/vcpkg) at the commit pinned
  in [`android/tools/vcpkg-deps.sh`](android/tools/vcpkg-deps.sh), which
  installs the libraries with this repository's patched ports
  ([`vcpkg-overlay-ports`](vcpkg-overlay-ports)).
- Meson 1.5 or newer (with PyYAML available to its Python), Ninja and
  pkg-config, plus autoconf, automake and libtool for vcpkg. macOS and Linux
  both work.

```bash
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/29.0.14206865
export VCPKG_ROOT=~/projects/vcpkg

android/tools/vcpkg-deps.sh     # once: the arm64-android libraries
./rebuild_meson.sh              # stage 1: configure and build the core
cd android && ./gradlew assembleRelease
```

The [Android workflow](.github/workflows/android.yml) runs exactly these steps
on every push and keeps the APK as a build artifact.

[`CLAUDE.md`](CLAUDE.md) documents the build in detail: the archive steps
needed after editing core files, compiler flags that must match on both sides,
profile-guided optimisation, and the common pitfalls.

## Performance

Most of this fork's changes are performance work, measured on the device. Some
of the larger wins:

- **Idle and spin-loop halting.** When the guest kernel idles, or a game spins
  waiting on a timer, the emulated CPU now sleeps until the next interrupt. This
  cut emulated-CPU time by about a quarter and lowered temperatures by about
  10 °C, which keeps clocks up on a passively limited handheld.
- **x87 floating point on native hardware**, and faster translated-code lookups.
- **A leaner NV2A GPU thread**: batched locking, cheaper dirty checks,
  zero-copy display, and quieter logging. Together these took its per-frame
  time from about 19 ms to about 9 ms.
- **Profile-guided optimisation** of the core, and an ARMv8.6-A target.

[`perf-investigation/00-FINDINGS.md`](perf-investigation/00-FINDINGS.md) is the
full record: the measurements, the method, and the ideas that were tried and
did *not* help.

## Relationship to xemu

Boxxy tracks xemu's `master` branch and brings in upstream fixes regularly, so
improvements to xemu's emulation reach Android too. The Android app, the
display path, and most of the CPU and GPU-thread performance work are specific
to this fork.

For the xemu project itself (desktop builds, documentation and community), see
[xemu.app](https://xemu.app) and
[xemu-project/xemu](https://github.com/xemu-project/xemu).

## License

Boxxy is licensed under the GNU General Public License, version 2, like xemu
and QEMU. See [`LICENSE`](LICENSE) and [`COPYING`](COPYING). Third-party
licenses are in [`licenses/`](licenses).

Xbox and Xbox Live are trademarks of Microsoft. Boxxy is not affiliated with
or endorsed by Microsoft. Online play uses [Insignia](https://insignia.live), an
independent revival of the original Xbox Live; Boxxy is not affiliated with
Insignia either. Boxxy does not include any Microsoft software or games; you need
your own system files and games.
