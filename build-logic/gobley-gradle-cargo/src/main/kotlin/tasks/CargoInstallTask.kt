/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.cargo.tasks

import gobley.gradle.GobleyHost
import gobley.gradle.InternalGobleyGradleApi
import gobley.gradle.cargo.dsl.CargoBinaryCrateSource
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.io.File

@CacheableTask
abstract class CargoInstallTask : CargoTask() {
    @get:Input
    val quiet: Property<Boolean> = project.objects.property<Boolean>().convention(true)

    @get:Input
    abstract val binaryCrateSource: Property<CargoBinaryCrateSource>

    /**
     * Every file of a [CargoBinaryCrateSource.Path] crate, so editing it re-runs `cargo install`.
     * Not only `.rs`: the bindgen embeds its askama templates at compile time (SQ-2408).
     */
    @Suppress("LeakingThis", "unused")
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    internal val pathSourceFiles: Provider<List<File>> = binaryCrateSource.map { source ->
        if (source !is CargoBinaryCrateSource.Path) return@map emptyList()
        File(source.path).walk()
            .onEnter { it.name !in arrayOf("target", "build", ".gradle", ".idea") }
            .filter { it.isFile }
            .toList()
    }

    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty
    protected fun binaryCrateOutput(crateName: String): RegularFileProperty {
        return project.objects.fileProperty()
            .convention(
                installDirectory.file(
                    GobleyHost.current.platform.convertExeName("bin/$crateName")
                )
            )
    }

    // TODO: Make this public in the next version
    @InternalGobleyGradleApi
    @get:Input
    internal val locked: Property<Boolean> = project.objects.property<Boolean>().convention(true)

    @TaskAction
    fun installBinaries() {
        @OptIn(InternalGobleyGradleApi::class)
        cargo("install") {
            arguments("--root", installDirectory)
            arguments("--target", GobleyHost.current.rustTarget.rustTriple)
            if (quiet.get()) {
                arguments("--quiet")
            }
            if (locked.get()) {
                arguments("--locked")
            }

            when (val source = binaryCrateSource.get()) {
                is CargoBinaryCrateSource.Registry -> {
                    arguments("${source.packageName}@${source.version}")
                    if (source.registry != null) {
                        arguments("--registry", source.registry)
                    }
                }

                is CargoBinaryCrateSource.Path -> arguments("--path", source.path)
                is CargoBinaryCrateSource.Git -> {
                    arguments("--git", source.repository)
                    when (source.commit) {
                        is CargoBinaryCrateSource.Git.Commit.Branch -> arguments(
                            "--branch",
                            source.commit.branch
                        )

                        is CargoBinaryCrateSource.Git.Commit.Tag -> arguments(
                            "--tag",
                            source.commit.tag
                        )

                        is CargoBinaryCrateSource.Git.Commit.Revision -> arguments(
                            "--rev",
                            source.commit.revision
                        )

                        else -> {}
                    }
                }
            }
            suppressXcodeIosToolchains()
        }.get().assertNormalExitValue()
    }
}