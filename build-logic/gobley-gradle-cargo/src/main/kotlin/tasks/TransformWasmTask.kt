/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.cargo.tasks

import gobley.gradle.InternalGobleyGradleApi
import gobley.gradle.tasks.CommandTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@OptIn(InternalGobleyGradleApi::class)
@CacheableTask
abstract class TransformWasmTask : CommandTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmTransformer: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val crateName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val functionImportsFile: RegularFileProperty

    /**
     * Optional output directory for the per-crate Kotlin/Wasm JavaScript shim
     * (`gobley_<crate>_wasmjs_helpers.mjs`). When set, the transformer also
     * runs its `KotlinWasmJsHelpersRenderer` against the input WASM and writes
     * the bridge module here. Wired by [gobley.gradle.cargo.CargoPlugin] when
     * the project declares a `wasmJs()` Kotlin target alongside the existing
     * `js()` one — see T0.C.6.
     *
     * Left unset for `kotlin("js")`-only consumers, in which case only the
     * Kotlin/JS file in [outputDirectory] is generated (back-compat with the
     * pre-T0.C.6 pipeline).
     */
    @get:OutputDirectory
    @get:Optional
    abstract val wasmJsHelpersOutputDirectory: DirectoryProperty

    /**
     * Optional output directory for the Kotlin/Wasm-flavored Kotlin file
     * (`<package>.kt`). When set, the transformer also runs its
     * `KotlinWasmJsRenderer` against the input WASM and writes a Kotlin/Wasm-
     * compatible source file here, in addition to the Kotlin/JS file at
     * [outputDirectory]. Wired by [gobley.gradle.cargo.CargoPlugin] when
     * the project declares a `wasmJs()` Kotlin target — see T0.C.6.b.
     *
     * Why a separate output directory rather than reusing [outputDirectory]:
     * the Kotlin/JS file emitted to [outputDirectory] uses Kotlin/JS-only
     * features (`kotlin.Any` in `external fun`, `org.khronos.webgl.ArrayBuffer`,
     * nested classes inside interfaces, `dynamic`) that Kotlin/Wasm 2.1.10
     * rejects with 200+ errors. Both source sets cannot share the same
     * generated `.kt` — they need physically distinct files. We could put
     * the wasmJs file inside [outputDirectory] under a subdirectory, but
     * Gradle source set scanning is recursive and the Kotlin/JS source set
     * would then try to compile the wasmJs file too. Keeping the wasmJs
     * output in a sibling directory (rather than a child) avoids that
     * crossfire entirely.
     *
     * Left unset for `kotlin("js")`-only consumers, in which case only the
     * Kotlin/JS file in [outputDirectory] is generated (back-compat with the
     * pre-T0.C.6.b pipeline).
     */
    @get:OutputDirectory
    @get:Optional
    abstract val wasmJsKotlinOutputDirectory: DirectoryProperty

    @TaskAction
    fun transformWasm() {
        @OptIn(InternalGobleyGradleApi::class)
        command(wasmTransformer) {
            input.get().asFile.parentFile?.run {
                if (!exists()) {
                    mkdirs()
                }
            }
            outputDirectory.get().asFile.run {
                if (!exists()) {
                    mkdirs()
                }
            }
            val packageName = "gobley.wasm.${crateName.get().replace('-', '_')}"
            arguments("--input", input.get())
            arguments("--output", outputDirectory.get().file("$packageName.kt"))
            arguments("--package-name", packageName)
            if (functionImportsFile.isPresent) {
                arguments("--function-imports-file", functionImportsFile.get())
            }
            if (wasmJsHelpersOutputDirectory.isPresent) {
                val helpersDir = wasmJsHelpersOutputDirectory.get().asFile
                if (!helpersDir.exists()) {
                    helpersDir.mkdirs()
                }
                val mjsFileName = "gobley_${crateName.get().replace('-', '_')}_wasmjs_helpers.mjs"
                arguments(
                    "--mjs-output",
                    wasmJsHelpersOutputDirectory.get().file(mjsFileName),
                )
                arguments("--crate-name", crateName.get())
            }
            if (wasmJsKotlinOutputDirectory.isPresent) {
                val wasmJsKotlinDir = wasmJsKotlinOutputDirectory.get().asFile
                if (!wasmJsKotlinDir.exists()) {
                    wasmJsKotlinDir.mkdirs()
                }
                arguments(
                    "--wasmjs-output",
                    wasmJsKotlinOutputDirectory.get().file("$packageName.kt"),
                )
            }
        }.get().apply {
            assertNormalExitValueUsingLogger()
        }
    }
}
