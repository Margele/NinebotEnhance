# Third-party notices

Ninebot Enhance — Copyright 2026 Ninebot Enhance contributors.

Original project code is licensed under the Apache License, Version 2.0 (see `LICENSE`, also packaged as `META-INF/licenses/NinebotEnhance-Apache-2.0.txt`). Third-party material retains its own copyrights and licenses. Full applicable license texts are included under `app/src/main/resources/META-INF/licenses/` and in the APK at `META-INF/licenses/`. This notice is also packaged as `META-INF/NOTICE.txt`.

Upstream license declarations were checked on 2026-09-13. A reference to an external project does not relicense its code.

## Bundled runtime dependency

**Shizuku-API 13.1.5** — Copyright (c) 2021 RikkaW and contributors.

- Upstream: https://github.com/RikkaApps/Shizuku-API
- Artifacts: `dev.rikka.shizuku:api`, `aidl`, `shared`, `provider`, all version 13.1.5.
- License: MIT; full notice and license in `Shizuku-MIT.txt`.
- License source: https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE; the published 13.1.5 POM also declares MIT: https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom
- The official API, Sui initialization and provider classes are bundled. The Shizuku server, manager application and Sui module are not bundled.

## Bundled resources

**Liberation Sans** — Digitized data copyright (c) 2010 Google Corporation with Reserved Font Arimo; Copyright (c) 2012 Red Hat, Inc. with Reserved Font Name Liberation.

- File: `app/src/main/res/font/nb_sans.ttf`, packaged in the APK as `res/font/nb_sans.ttf`.
- Purpose: the module's own dashboard text when the default-font switch is on, so the readout does not follow a phone font.
- License: SIL Open Font License 1.1; full notice and license in `LiberationSans-OFL-1.1.txt`.
- Upstream: https://github.com/liberationfonts/liberation-fonts

## Adapted display implementation

The bootstrap, minimal ActivityThread context, display creation, activity launch and input injection in `display/RootDisplayMain.java` adapt the approach and relevant wrapper/workaround code from these Apache-2.0 projects. Local changes replace their video/network transport with authenticated Binder Surface handles, use shell identity before runtime startup, and bind display lifetime to the Ninebot session.

- **VirtualDisplay** — Copyright 2026 ynk: https://github.com/Ynkcc/VirtualDisplay
  - Reference commit: `67aabb32b87ff9ce90ff31b5e35f53b90ab8bb35`.
  - Referenced process/controller design; its Kotlin UI and protocol are not bundled.
  - License: https://github.com/Ynkcc/VirtualDisplay/blob/67aabb32b87ff9ce90ff31b5e35f53b90ab8bb35/LICENSE
- **scrcpy** — Copyright (C) 2018 Genymobile; Copyright (C) 2018-2026 Romain Vimont: https://github.com/Genymobile/scrcpy
  - Referenced fork: https://github.com/ynkcc/scrcpy
  - Reference commit: `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`.
  - Adapted FakeContext / Workarounds and DisplayManager / ActivityManager / InputManager wrapper ideas and reflective calls.
  - License: https://github.com/ynkcc/scrcpy/blob/2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0/LICENSE

License: Apache License, Version 2.0; full text in `Apache-2.0.txt`. Original attribution is preserved in the adapted source.

**VirtualDisplayDaemon** — Ynkcc and contributors: https://github.com/Ynkcc/VirtualDisplayDaemon/tree/0552fe9d9abfbde6e6a21ca71a86976c47b0ceba

Referenced display registry / launcher behavior. At this reference commit no standalone license is declared for this repository's own patch queue or scripts. Do not infer an Apache-2.0 grant for those files from the parent app or the scrcpy submodule. No daemon binary, patch queue or scripts from that repository are included in Ninebot Enhance. The separately licensed scrcpy source is identified above.

## Compile-only dependencies

These APIs are used to compile the project and are not bundled into the APK.

- **libxposed API 101.0.1** — https://github.com/libxposed/api
  - `io.github.libxposed:api:101.0.1`, Apache-2.0; supplied at runtime by LSPosed.
  - https://github.com/libxposed/api/blob/101.0.1/LICENSE
- **AndroidX Annotation 1.3.0** — The Android Open Source Project
  - `androidx.annotation:annotation:1.3.0`, Apache-2.0.
  - https://developer.android.com/jetpack/androidx/releases/annotation
  - Published license declaration: https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.pom

Vendored JAR / AAR checksums are recorded in `libs/checksums.json` and checked by the release build script.

## Build tooling

**Gradle Wrapper 8.13** — Gradle, Inc. and contributors, Apache-2.0: https://github.com/gradle/gradle/tree/v8.13.0/gradle/wrapper

The wrapper scripts preserve their upstream license headers. Wrapper JAR SHA-256: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`. The distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`. Build tooling is not bundled in the APK.

## External services and platform references

These services, managers and Root modules are installed separately and are not bundled in this APK. Shizuku / Sui integration uses the MIT API identified above, not their server implementation.

- **Shizuku server / manager**: Apache-2.0, https://github.com/RikkaApps/Shizuku/blob/master/LICENSE
- **Sui module**: GPLv3, https://github.com/RikkaApps/Sui/blob/master/LICENSE
- **LSPosed framework**: GPLv3, https://github.com/LSPosed/LSPosed/blob/master/LICENSE (libxposed API has its separate license above).
- **KernelSU**: root repository license GPLv3, https://github.com/tiann/KernelSU/blob/main/LICENSE. Only the `su` command's identity-switch behavior was consulted; no KernelSU code or binary is bundled. Component-level licenses remain authoritative for KernelSU itself.

No additional third-party binaries are bundled from the platform references below. The cited AOSP framework source carries Apache-2.0 headers. Android SDK/build tools are external build prerequisites, not APK dependencies.

- https://developer.android.com/reference/android/hardware/display/VirtualDisplayConfig.Builder
- https://github.com/aosp-mirror/platform_frameworks_base/tree/android16-release
- https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/su.rs
