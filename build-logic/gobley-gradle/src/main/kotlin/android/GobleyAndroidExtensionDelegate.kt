/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.android

import gobley.gradle.InternalGobleyGradleApi
import gobley.gradle.tasks.InjectJniLibsTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * Callback type used by [GobleyAndroidExtensionDelegate.onVariants].
 *
 * Parameters:
 *  - agpVariantName: the AGP variant name (e.g. "debug", "release")
 *  - cargoVariantName: the Cargo variant name ("debug" or "release")
 *  - onMainTask: registers the JNI inject task as a generated source dir for the main component
 *  - onTestTask: registers the JNI inject task for the androidTest component (null when unavailable)
 */
typealias OnVariantAction = (
    agpVariantName: String,
    cargoVariantName: String,
    onMainTask: (TaskProvider<InjectJniLibsTask>) -> Unit,
    onTestTask: ((TaskProvider<InjectJniLibsTask>) -> Unit)?
) -> Unit

@InternalGobleyGradleApi
interface GobleyAndroidExtensionDelegate {
    val androidSdkRoot: File
    val androidMinSdk: Int
    val androidNdkRoot: File?
    val androidNdkVersion: String?
    val abiFilters: Set<String>

    /**
     * Registers a generated source directory for Kotlin bindings using the AGP variant API.
     * Used by UniFFI plugin to wire bindgen output into Android compilation.
     */
    fun <T : Task> addGeneratedBindingsDirectory(
        project: Project,
        taskProvider: TaskProvider<T>,
        directoryMapping: (T) -> DirectoryProperty,
    )

    /**
     * Called once per AGP variant to wire [InjectJniLibsTask] outputs into the
     * jniLibs source set for both the main and androidTest components.
     */
    fun onVariants(
        project: Project,
        action: OnVariantAction,
    )

    /**
     * Adds proguard/keep-rules files for the given generation task.
     */
    fun addProguardFiles(
        project: Project,
        proguardFileProvider: Provider<RegularFile>,
        generationTask: TaskProvider<*>,
    )

    /**
     * Legacy overload retained for UniFFI plugin compatibility.
     * Wraps the [RegularFile] in a plain provider and delegates to the primary overload.
     */
    fun addProguardFiles(
        project: Project,
        proguardFile: RegularFile,
        generationTask: TaskProvider<*>,
    ) {
        addProguardFiles(project, project.provider { proguardFile }, generationTask)
    }

    /**
     * Adds a generated Kotlin source directory. Used by UniFFI plugin so Android Studio
     * recognises the bindgen output in the Android source set.
     *
     * Default implementation is a no-op.
     */
    fun addMainSourceDir(
        variant: gobley.gradle.Variant? = null,
        sourceDirectory: Provider<org.gradle.api.file.Directory>,
    ) = Unit
}
