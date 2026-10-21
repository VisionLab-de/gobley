# Changelog

## Fork changes (VisionLab-de/gobley, branch `wasm`)

Changes made in the VisionLab-de/gobley fork. It is untagged and unreleased, and based on upstream commit `a4d0a0e` (2026-03-26, `v0.3.7` plus 12 upstream commits). The upstream sections below are unchanged.

### Added

- Added a `wasmJs` Kotlin binding backend to `gobley-uniffi-bindgen` (`ConfigKotlinTarget::WasmJs`, `templates/wasm-js/`) that emits `wasmJsMain/.../<ns>.wasmJs.kt` for Kotlin/Wasm, calling the raw `.wasm` through `@JsFun` and `WebAssembly.Memory`.
- Added callback-interface support on `wasmJs` (`@JsExport` dispatchers, `HandleMap`, vtable lookup through `__indirect_function_table`) and async support in both directions, including the foreign-future completer.
- Added `wasmJs` wiring in `CargoPlugin` and `UniFfiPlugin`: `wasm32-unknown-unknown` cdylib build, bindings added to `wasmJsMain`, and `transformWasm*` tasks.
- Added `--mjs-output`, `--crate-name` and `--wasmjs-output` to `gobley-wasm-transformer` (JS shim plus a Kotlin/Wasm-compatible `.kt`); `TransformWasmTask` gained `wasmJsHelpersOutputDirectory` and `wasmJsKotlinOutputDirectory`.
- Added the `cargo { wasmTransformerEnabled }` property (default `false`); the transformer tasks are skipped unless it is `true`.
- Added multi-crate bindings: `BindingsGeneration.crates` and the bindgen `--crates a,b` allowlist, with namespace-qualified async callbacks (`gobley_<ns>_*`).
- Added bindgen `--exported-lib` (requires `--library`), which derives the crate allowlist from the cdylib export table; `UniFfiPlugin` passes the cdylib automatically.
- Added support for the `com.android.kotlin.multiplatform.library` target (port of gobley/gobley#280) via `GobleyAndroidKotlinMultiplatformExtensionDelegate`.
- Added the `gobley.android.abis` Gradle property (e.g. `-Pgobley.android.abis=arm64-v8a`) to limit the Android Rust targets built; it also applies to the KMP `androidJvm` target.
- Added the `gobley.skipAndroidUnitTest` Gradle property and a `gobley-rust-android-unit-test` usage attribute on the Rust runtime configurations, for projects that disable `androidHostTest`.

### Changed

- Changed the bindgen input to prefer the `staticlib` over the cdylib, so `strip = "symbols"` cannot remove the `UNIFFI_META_*` symbols.
- Changed `installUniffiBindgen` (`CargoInstallTask`) to declare every file of a path-source crate as an input, so edited bindgen templates are no longer reported UP-TO-DATE.
- Changed wasmJs helper loading: the helper `.mjs` is embedded as a base64 data URL, the dynamic `import()` goes through `new Function` (no webpack "critical dependency" warning), and the host must provide `globalThis.__gobleyKotlinExports` before `init`.
- Changed the fork's build toolchain to Kotlin 2.4.20, AGP 9.0.0, Gradle wrapper 9.8.0 and kotlinx-coroutines 1.11.0; removed Gradle 10 deprecated usages. Fork crate versions are `gobley-uniffi-bindgen` 0.3.9 and `gobley-wasm-transformer` 0.3.11.
- Changed `gradle.properties` to set all `gobley.projects.examples.*`, `gradleTests` and `uniffiTests*` flags to `false`; re-enable per flag.

### Fixed

- Fixed the AGP 9 NPE in the JNI lib wiring (`MergeSourceSetFolders.variant` is null) by using `androidComponents.onVariants` and a new `InjectJniLibsTask`, replacing `GobleyKotlinAndroidExtensionDelegate` (port of gobley/gobley#282).
- Fixed the generated wasmJs `RustBuffer` layout (24 bytes, `u64` capacity and len), `UniffiRustCallStatus` (32 bytes), FFI struct field accessors, `Long` params split into Int pairs, and `i64`/`u64` returns.
- Fixed wasmJs `ByteBuffer` to use big-endian for the UniFFI wire format (little-endian only for C ABI struct fields).
- Fixed wasmJs compile errors: dropped `@JvmInline` and the duplicate `*UniffiByValue` factory, and replaced `@Throws` with `@Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")`.
- Fixed Kotlin/Wasm crashes on very large `GOBLEY_WASM_BASE64` literals by emitting it in 16 KB chunks.
- Fixed wasmJs async callback interfaces throwing at runtime: the foreign-future completer and `ForeignFuture.free` are now wired, and `@JsExport` callback exports are safe.
- Fixed `wasmExports` being an unusable Proxy under Kotlin 2.4.20 (KT-86242): `init` now awaits `globalThis.__gobleyKotlinExports`.

## [Unreleased](https://github.com/gobley/gobley/compare/v0.3.7...HEAD)

## [0.3.7](https://github.com/gobley/gobley/releases/tag/v0.3.7) - 2025-10-08

### Fixes

- Fixed the regression where the dynamic library dependency loading logic is missing ([#237](https://github.com/gobley/gobley/pull/237) & [#240](https://github.com/gobley/gobley/pull/240)).

## [0.3.6](https://github.com/gobley/gobley/releases/tag/v0.3.6) - 2025-10-04

### Fixes

- Made the Cargo plugin put the Android NDK sysroot to `BINDGEN_EXTRA_CLANG_ARGS_<target>` when invoking Cargo builds ([#224](https://github.com/gobley/gobley/pull/224) & [#231](https://github.com/gobley/gobley/pull/231)).

### Behavior Changes

- Made the bindgen use the JNA direct mapping ([#227](https://github.com/gobley/gobley/pull/227)).
- Removed `RustBufferByReference` from the UniFFI bindings ([#232](https://github.com/gobley/gobley/pull/232)).

### Dependencies

- Upgraded the default JNA version from 5.17.0 to 5.18.1 ([#229](https://github.com/gobley/gobley/pull/229)).

## [0.3.5](https://github.com/gobley/gobley/releases/tag/v0.3.5) - 2025-09-16

### Fixes

- Made `FindDynamicLibrariesTask` find dynamic libraries from Cargo build script `OUT_DIR` by default ([#213](https://github.com/gobley/gobley/pull/213)).
- Ensured Gobley works on a Rosetta-emulated x64 JVM on Apple Silicon macOS ([#217](https://github.com/gobley/gobley/pull/217)).

## [0.3.4](https://github.com/gobley/gobley/releases/tag/v0.3.4) - 2025-08-22

### Fixes

- Fixed empty field names being used in `equals()` of tuple variants of enums ([#194](https://github.com/gobley/gobley/pull/194)).

## [0.3.3](https://github.com/gobley/gobley/releases/tag/v0.3.3) - 2025-08-20

### Fixes

- Made the bindgen use `kotlinx.cinterop.convert` to cast `Int` to `size_t` ([#187](https://github.com/gobley/gobley/pull/187)).

## [0.3.2](https://github.com/gobley/gobley/releases/tag/v0.3.2) - 2025-08-17

### New Features

- Android NDK Kotlin/Native target support ([#177](https://github.com/gobley/gobley/pull/177)).

### Fixes

- Made `jarJvmRustRuntime` tasks use `jnaResourcePrefix` if `resourcePrefix` is empty ([#178](https://github.com/gobley/gobley/pull/178)).
- Made `CargoBuildTask` print human-readable error messages ([#180](https://github.com/gobley/gobley/pull/180)).
- Made `CargoBuildTask` replace backslashes in paths with slashes when rendering `nativeStaticLibsDefFile` on Windows ([#183](https://github.com/gobley/gobley/pull/183)).

### Dependencies

- Deleted the dependency on Okio ([#176](https://github.com/gobley/gobley/pull/176)).

## [0.3.1](https://github.com/gobley/gobley/releases/tag/v0.3.1) - 2025-08-10

### Fixes

- Prioritized shared libraries over static libraries when generating UniFFI bindings ([#165](https://github.com/gobley/gobley/pull/165)).
- Explicit visibility modifier in UniFFI bindings ([#169](https://github.com/gobley/gobley/pull/169)).

### Dependencies

- Upgraded UniFFI from 0.29.3 to 0.29.4 ([#167](https://github.com/gobley/gobley/pull/167)).

## [0.3.0](https://github.com/gobley/gobley/releases/tag/v0.3.0) - 2025-08-04

### New Features

- `CargoCheckTask.extraArguments` ([#110](https://github.com/gobley/gobley/pull/110)).
- `CargoExtension.installTargetBeforeBuild` to prevent `RustUpTargetAddTasks` from running ([#136](https://github.com/gobley/gobley/pull/136)).
- ProGuard rule file generation for UniFFI ([#140](https://github.com/gobley/gobley/pull/140)).
- UniFFI 0.29.3 support ([#143](https://github.com/gobley/gobley/pull/143) & [#147](https://github.com/gobley/gobley/pull/147)).
- Bindgen option to bypass checksum checks ([#144](https://github.com/gobley/gobley/pull/144)).
- WASM embedding into Kotlin/JS ([#151](https://github.com/gobley/gobley/pull/151)).

### Fixes

- Fixed the build script library search path parsing logic using `--message-format json` ([#113](https://github.com/gobley/gobley/pull/113)).
- Fixed MinGW DLL files not being included in the JAR files ([#137](https://github.com/gobley/gobley/pull/137)).
- Implemented workaround for `MethodTooLargeException` thrown from JNA ([#145](https://github.com/gobley/gobley/pull/145)).
- Implemented proper handling of artifact resolution ([#158](https://github.com/gobley/gobley/pull/158)).
- Fixed the Android API level conflict due to redundant C/CXX environment variables ([#159](https://github.com/gobley/gobley/pull/159)).

## [0.2.0](https://github.com/gobley/gobley/releases/tag/v0.2.0) - 2025-04-06

### Breaking Changes

- Separate artifacts for JVM runtime Rust dynamic
  libraries ([#81](https://github.com/gobley/gobley/pull/81) & [#102](https://github.com/gobley/gobley/pull/102)).

### New Features

- Stub generation for unsupported JS/WASM targets ([#29](https://github.com/gobley/gobley/pull/29)).
- Kotlin/Native tvOS & watchOS support ([#35](https://github.com/gobley/gobley/pull/35)).
- Kotlin JVM and Kotlin Android Gradle plugins
  support ([#44](https://github.com/gobley/gobley/pull/44), [#47](https://github.com/gobley/gobley/pull/47), [#87](https://github.com/gobley/gobley/pull/87), [#94](https://github.com/gobley/gobley/pull/94), & [#97](https://github.com/gobley/gobley/pull/97)).
- Config option for using PascalCase for enums ([#54](https://github.com/gobley/gobley/pull/54)).
- `CargoCheckTask` for cross-platform linting ([#55](https://github.com/gobley/gobley/pull/55)).
- `UniFfiPlugin` can be used without a UniFFI config
  file ([#63](https://github.com/gobley/gobley/pull/63)).
- UniFFI bindings generation during IDE sync ([#101](https://github.com/gobley/gobley/pull/101)).

### Fixes

- Ensured interface instances in lists and maps are
  destroyed ([#53](https://github.com/gobley/gobley/pull/53)).
- Prevented `UniFfiPlugin` from selecting a build excluded by
  `embedRustLibrary` ([#64](https://github.com/gobley/gobley/pull/64)).
- Prevented NPE occurring when functions returning RustArcPtr throw
  errors ([#75](https://github.com/gobley/gobley/pull/75)).

## Dependencies

- Upgraded JNA from 5.16.0 to 5.17.0 ([#65](https://github.com/gobley/gobley/pull/65)).
- Kotlin library version requirements are eased
  off ([#89](https://github.com/gobley/gobley/pull/89)).

## [0.1.0](https://github.com/gobley/gobley/releases/tag/v0.1.0) - 2025-03-03

### New Features

- Support for `uniffi@0.28.3`.
- Support for Kotlin 2.x.
- Gradle plugins for cross-compilation automation.
  - Android
  - JVM
  - Kotlin/Native (MinGW, macOS, Linux, & iOS)

## [UniFFI Kotlin Multiplatform Bindings 0.1.0](https://gitlab.com/trixnity/uniffi-kotlin-multiplatform-bindings/-/tags/v0.1.0) - 2023-11-25

### Added

- Support for `uniffi@0.25.2`
