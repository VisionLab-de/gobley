/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.cargo

import com.android.build.gradle.internal.tasks.factory.dependsOn
import gobley.gradle.AppleSdk
import gobley.gradle.GobleyHost
import gobley.gradle.InternalGobleyGradleApi
import gobley.gradle.PluginIds
import gobley.gradle.Variant
import gobley.gradle.android.GobleyAndroidExtensionDelegate
import gobley.gradle.cargo.dsl.CargoAndroidBuild
import gobley.gradle.cargo.dsl.CargoAndroidBuildVariant
import gobley.gradle.cargo.dsl.CargoExtension
import gobley.gradle.cargo.dsl.CargoJvmBuild
import gobley.gradle.cargo.dsl.CargoJvmBuildVariant
import gobley.gradle.cargo.dsl.CargoNativeBuild
import gobley.gradle.cargo.dsl.CargoNativeBuildVariant
import gobley.gradle.cargo.dsl.CargoWasmBuild
import gobley.gradle.cargo.dsl.CargoWasmBuildVariant
import gobley.gradle.cargo.dsl.jvm
import gobley.gradle.cargo.dsl.native
import gobley.gradle.cargo.dsl.wasm
import gobley.gradle.cargo.tasks.CargoCleanTask
import gobley.gradle.cargo.tasks.CargoTask
import gobley.gradle.cargo.tasks.InstallWasmTransformerTask
import gobley.gradle.cargo.tasks.RustUpTargetAddTask
import gobley.gradle.cargo.tasks.RustUpTask
import gobley.gradle.cargo.utils.register
import gobley.gradle.kotlin.GobleyKotlinExtensionDelegate
import gobley.gradle.rust.CrateType
import gobley.gradle.rust.targets.RustAndroidTarget
import gobley.gradle.rust.targets.RustJvmTarget
import gobley.gradle.rust.targets.RustTarget
import gobley.gradle.rust.targets.RustWasmTarget
import gobley.gradle.tasks.useGlobalLock
import gobley.gradle.utils.DependencyUtils
import gobley.gradle.utils.GradleUtils
import gobley.gradle.utils.PluginUtils
import gobley.gradle.variant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinWithJavaTarget
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

class CargoPlugin : Plugin<Project> {
    companion object {
        internal const val TASK_GROUP = "cargo"
    }

    private lateinit var cargoExtension: CargoExtension

    @OptIn(InternalGobleyGradleApi::class)
    private lateinit var kotlinExtensionDelegate: GobleyKotlinExtensionDelegate

    @OptIn(InternalGobleyGradleApi::class)
    private lateinit var androidDelegate: GobleyAndroidExtensionDelegate

    override fun apply(target: Project) {
        @OptIn(InternalGobleyGradleApi::class)
        if (!target.plugins.hasPlugin(PluginIds.GOBLEY_RUST)) {
            DependencyUtils.createCargoConfigurations(target)
        }
        cargoExtension = target.extensions.create<CargoExtension>(TASK_GROUP, target)
        cargoExtension.jvmVariant.convention(Variant.Debug)
        cargoExtension.jvmPublishingVariant.convention(Variant.Release)
        cargoExtension.nativeVariant.convention(Variant.Debug)
        cargoExtension.wasmVariant.convention(Variant.Debug)
        readVariantsFromXcode()
        cargoExtension.builds.native {
            nativeVariant.convention(
                cargoExtension.nativeTargetVariantOverride.getting(rustTarget)
                    .orElse(cargoExtension.nativeVariant)
            )
        }
        cargoExtension.builds.jvm {
            jvmVariant.convention(cargoExtension.jvmVariant)
            jvmPublishingVariant.convention(cargoExtension.jvmPublishingVariant)
        }
        cargoExtension.builds.wasm {
            wasmVariant.convention(cargoExtension.wasmVariant)
        }
        @OptIn(InternalGobleyGradleApi::class)
        target.useGlobalLock()
        target.tasks.withType<CargoTask>().configureEach {
            additionalEnvironmentPath.add(cargoExtension.toolchainDirectory)
        }
        target.tasks.withType<RustUpTask>().configureEach {
            additionalEnvironmentPath.add(cargoExtension.toolchainDirectory)
        }
        target.watchPluginChanges()
        target.afterEvaluate {
            target.checkRequiredPlugins()
            target.checkKotlinTargets()
            applyAfterEvaluate(this)
        }
    }

    private fun applyAfterEvaluate(target: Project): Unit = with(target) {
        checkRequiredCrateTypes()
        if (cargoExtension.builds.isEmpty()) {
            logger.warn("No Kotlin targets detected.")
            return
        }

        configureBuildTasks()
        configureCleanTasks()

        @OptIn(InternalGobleyGradleApi::class)
        DependencyUtils.resolveCargoDependencies(target)
    }

    @OptIn(InternalGobleyGradleApi::class)
    private fun Project.watchPluginChanges() {
        PluginUtils.withKotlinPlugin(this) { delegate ->
            kotlinExtensionDelegate = delegate
            kotlinExtensionDelegate.targets.configureEach { planBuilds() }
        }
        PluginUtils.withAndroidPlugin(this) { delegate ->
            androidDelegate = delegate
            val abiFilters = androidDelegate.abiFilters
            cargoExtension.androidTargetsToBuild.convention(project.provider {
                if (abiFilters.isNotEmpty()) {
                    abiFilters.map(::RustAndroidTarget)
                } else {
                    RustAndroidTarget.values().toList()
                }
            })
        }
    }

    private fun Project.checkRequiredPlugins() {
        @OptIn(InternalGobleyGradleApi::class)
        PluginUtils.ensurePluginIsApplied(
            this,
            PluginUtils.PluginInfo(
                "Kotlin Multiplatform",
                PluginIds.KOTLIN_MULTIPLATFORM
            ),
            PluginUtils.PluginInfo(
                "Kotlin Android",
                PluginIds.KOTLIN_ANDROID,
            ),
            PluginUtils.PluginInfo(
                "Kotlin JVM",
                PluginIds.KOTLIN_JVM,
            ),
        )
    }

    private fun KotlinTarget.planBuilds() {
        for (rustTarget in requiredRustTargets()) {
            cargoExtension.createOrGetBuild(rustTarget).kotlinTargets.add(this)
        }
    }

    private fun KotlinTarget.requiredRustTargets(): List<RustTarget> {
        return when (platformType) {
            KotlinPlatformType.jvm -> {
                GobleyHost.current.platform.supportedTargets.filterIsInstance<RustJvmTarget>()
            }

            KotlinPlatformType.androidJvm -> {
                // listOf(GobleyHost.current.rustTarget) is for Android local unit tests.
                listOf(GobleyHost.current.rustTarget) + RustAndroidTarget.values()
            }

            KotlinPlatformType.native -> {
                listOf(RustTarget((this as KotlinNativeTarget).konanTarget))
            }

            KotlinPlatformType.js -> {
                RustWasmTarget.values().toList()
            }

            KotlinPlatformType.wasm -> {
                // T0.C.6: Kotlin/Wasm (wasmJs / wasmWasi) reuses the same
                // wasm32-unknown-unknown cdylib path as Kotlin/JS. The
                // additional .mjs JS bridge is wired downstream in
                // configureWasmJsCompilation when this Kotlin target is
                // attached to a CargoWasmBuild.
                RustWasmTarget.values().toList()
            }

            else -> listOf()
        }
    }

    @OptIn(InternalGobleyGradleApi::class)
    private fun Project.checkKotlinTargets() {
        // T0.C.6: Kotlin/Wasm support added. Pre-T0.C.6 the plugin warned
        // here that WASM targets were unsupported; that warning is now stale.

        val hasAndroidJvmTargets =
            kotlinExtensionDelegate.targets.any { it.platformType == KotlinPlatformType.androidJvm }
        if (hasAndroidJvmTargets && !::androidDelegate.isInitialized) {
            throw GradleException("Android JVM targets are added, but Android Gradle Plugin is not found.")
        }
    }

    private fun checkRequiredCrateTypes() {
        val requiredCrateTypes = cargoExtension
            .builds
            .flatMap { it.kotlinTargets }
            .map { it.platformType.requiredCrateType() }
            .distinct()
        val actualCrateTypes = cargoExtension.cargoPackage.get().libraryCrateTypes
        if (!actualCrateTypes.containsAll(requiredCrateTypes)) {
            throw GradleException(
                "Crate does not have required crate types. Required: $requiredCrateTypes, actual: $actualCrateTypes"
            )
        }
    }

    @OptIn(InternalGobleyGradleApi::class)
    private fun readVariantsFromXcode() {
        val sdkName = System.getenv("SDK_NAME") ?: return
        val sdk = AppleSdk(sdkName)

        val configuration = System.getenv("CONFIGURATION") ?: return
        val variant = Variant(configuration)

        val archs = System.getenv("ARCHS")?.split(' ')?.map(AppleSdk::Arch) ?: return
        cargoExtension.nativeTargetVariantOverride.putAll(
            archs.mapNotNull(sdk::rustTarget).associateWith { variant })
    }

    private fun Project.configureBuildTasks() {
        val androidTarget = cargoExtension.builds.firstNotNullOfOrNull { build ->
            build.kotlinTargets.firstNotNullOfOrNull { it as? KotlinAndroidTarget }
        }
        val jvmTarget = cargoExtension.builds.firstNotNullOfOrNull { build ->
            build.kotlinTargets.firstOrNull {
                it is KotlinJvmTarget || it is KotlinWithJavaTarget<*, *>
            }
        }
        val wasmTransformerEnabled = cargoExtension.wasmTransformerEnabled
        val wasmBindgenInstallTask =
            tasks.register<InstallWasmTransformerTask>("installWasmTransformer") {
                group = TASK_GROUP
                binaryCrateSource.set(cargoExtension.wasmTransformerSource)
                installDirectory.set(layout.buildDirectory.dir("gobley-tools-install/wasm-transformer"))
                onlyIf("wasmTransformerEnabled") { wasmTransformerEnabled.get() }
            }
        for (cargoBuild in cargoExtension.builds) {
            val rustUpTargetAddTask =
                tasks.register<RustUpTargetAddTask>({ +cargoBuild.rustTarget }) {
                    group = TASK_GROUP
                    this.rustTarget.set(cargoBuild.rustTarget)
                    this.rustVersion.set(cargoExtension.rustVersion)
                }
            for (cargoBuildVariant in cargoBuild.variants) {
                val projectLayout = layout
                cargoBuildVariant.buildTaskProvider.configure {
                    nativeStaticLibsDefFile.set(
                        projectLayout.outputCacheFile(
                            this,
                            "nativeStaticLibsDefFile",
                        )
                    )
                    buildScriptOutputDirectoriesFile.set(
                        projectLayout.outputCacheFile(
                            this,
                            "buildScriptOutputDirectoriesFile",
                        )
                    )
                    if (cargoBuild.installTargetBeforeBuild.get()) {
                        dependsOn(rustUpTargetAddTask)
                    }
                    if (cargoBuildVariant is CargoAndroidBuildVariant) {
                        @OptIn(InternalGobleyGradleApi::class)
                        val environmentVariables = cargoBuildVariant.rustTarget.ndkEnvVariables(
                            sdkRoot = androidDelegate.androidSdkRoot,
                            apiLevel = androidDelegate.androidMinSdk,
                            ndkVersion = androidDelegate.androidNdkVersion,
                            ndkRoot = androidDelegate.androidNdkRoot,
                        )
                        additionalEnvironment.putAll(environmentVariables)
                    }
                }
                cargoBuildVariant.checkTaskProvider.configure {
                    if (cargoBuild.installTargetBeforeBuild.get()) {
                        dependsOn(rustUpTargetAddTask)
                    }
                    if (cargoBuildVariant is CargoAndroidBuildVariant) {
                        @OptIn(InternalGobleyGradleApi::class)
                        val environmentVariables = cargoBuildVariant.rustTarget.ndkEnvVariables(
                            sdkRoot = androidDelegate.androidSdkRoot,
                            apiLevel = androidDelegate.androidMinSdk,
                            ndkVersion = androidDelegate.androidNdkVersion,
                            ndkRoot = androidDelegate.androidNdkRoot,
                        )
                        additionalEnvironment.putAll(environmentVariables)
                    }
                }
            }
            for (kotlinTarget in cargoBuild.kotlinTargets) {
                when (kotlinTarget.platformType) {
                    KotlinPlatformType.jvm -> {
                        cargoBuild as CargoJvmBuild<*>
                        cargoBuild.variants {
                            configureJvmPostBuildTasks(
                                kotlinTarget,
                                // cargoBuild.jvmVariant is checked inside
                                this,
                                androidTarget,
                            )
                        }
                    }

                    KotlinPlatformType.androidJvm -> {
                        if (cargoBuild is CargoJvmBuild<*>) {
                            if (jvmTarget == null) {
                                cargoBuild.variants {
                                    configureJvmPostBuildTasks(
                                        kotlinTarget,
                                        // cargoBuild.jvmVariant is checked inside
                                        this,
                                        kotlinTarget as KotlinAndroidTarget,
                                    )
                                }
                            }
                        } else {
                            cargoBuild as CargoAndroidBuild
                            cargoBuild.dynamicLibrarySearchPaths.addAll(
                                @OptIn(InternalGobleyGradleApi::class)
                                cargoBuild.rustTarget.ndkLibraryDirectories(
                                    sdkRoot = androidDelegate.androidSdkRoot,
                                    apiLevel = androidDelegate.androidMinSdk,
                                    ndkVersion = androidDelegate.androidNdkVersion,
                                    ndkRoot = androidDelegate.androidNdkRoot,
                                ),
                            )
                            Variant.values().forEach {
                                configureAndroidPostBuildTasks(cargoBuild.variant(it))
                            }
                        }
                    }

                    KotlinPlatformType.native -> {
                        cargoBuild as CargoNativeBuild<*>
                        configureNativeCompilation(
                            kotlinTarget as KotlinNativeTarget,
                            cargoBuild.variant(cargoBuild.nativeVariant.get())
                        )
                    }

                    KotlinPlatformType.js -> {
                        cargoBuild as CargoWasmBuild
                        configureWasmCompilation(
                            kotlinTarget as KotlinJsIrTarget,
                            cargoBuild.variant(cargoBuild.wasmVariant.get()),
                            wasmBindgenInstallTask,
                        )
                    }

                    KotlinPlatformType.wasm -> {
                        // T0.C.6: Kotlin/Wasm (wasmJs) reuses the same
                        // CargoWasmBuild as Kotlin/JS, but additionally
                        // requires the .mjs JS host bridge generated by
                        // gobley-wasm-transformer's KotlinWasmJsHelpersRenderer.
                        // configureWasmJsCompilation enables that and wires the
                        // resulting file into wasmJsMain resources.
                        cargoBuild as CargoWasmBuild
                        configureWasmJsCompilation(
                            kotlinTarget as KotlinJsIrTarget,
                            cargoBuild.variant(cargoBuild.wasmVariant.get()),
                            wasmBindgenInstallTask,
                        )
                    }

                    else -> {}
                }
            }
        }
    }

    private fun Project.configureJvmPostBuildTasks(
        // kotlinTarget can be KotlinAndroidTarget when the JVM target is not present. This is for
        // Android local unit tests.
        kotlinTarget: KotlinTarget,
        cargoBuildVariant: CargoJvmBuildVariant<*>,
        androidTarget: KotlinAndroidTarget?,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider
        val findDynamicLibrariesTask = cargoBuildVariant.findDynamicLibrariesTaskProvider
        val jarTask = cargoBuildVariant.jarTaskProvider
        cargoBuildVariant.dynamicLibrarySearchPaths.add(
            cargoBuildVariant.profile.zip(cargoExtension.cargoPackage) { profile, cargoPackage ->
                cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget).asFile
            }
        )
        cargoBuildVariant.dynamicLibrarySearchPaths.addAll(
            cargoBuildVariant.buildTaskProvider.flatMap { it.buildScriptOutputDirectories }
        )
        val projectLayout = layout
        findDynamicLibrariesTask.configure {
            libraryPathsCacheFile.set(projectLayout.outputCacheFile(this, "libraryPathsCacheFile"))
        }

        // For Kotlin/JVM projects without the application plugin or the Compose Multiplatform
        // plugin, the dynamic libraries must be copied in the resources directory to be loaded
        // during runtime. See #95.
        //
        // To avoid being included in the resources when class files are packaged into a JAR file,
        // `copyLibrariesTask` is invoked only when `GradleUtils.invokedByKotlinJvmBuild()`
        // returns `false`.
        val resourcePrefix = cargoBuildVariant.resourcePrefix.orNull?.takeIf(String::isNotEmpty)
        val resourceDirectory = layout.buildDirectory
            .dir("intermediates/rust/${cargoBuildVariant.rustTarget.rustTriple}/${cargoBuildVariant.variant}")
        val resourceCopyDestination = if (resourcePrefix == null) {
            resourceDirectory
        } else resourceDirectory.map {
            it.dir(resourcePrefix)
        }
        val copyLibrariesTask = tasks.register<Copy>({
            +"jvm"
            +cargoBuildVariant
        }) {
            from(cargoBuildVariant.libraryFiles)
            into(resourceCopyDestination)
            dependsOn(buildTask, findDynamicLibrariesTask)
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (
            kotlinTarget !is KotlinAndroidTarget
            && cargoBuildVariant.embedRustLibrary.get()
            && cargoBuildVariant.variant == cargoBuildVariant.build.jvmVariant.get()
        ) {
            val invokedByKotlinJvmBuild = GradleUtils.invokedByKotlinJvmBuild(gradle)
            if (invokedByKotlinJvmBuild) {
                val expectedTaskName = when (kotlinExtensionDelegate.pluginId) {
                    PluginIds.KOTLIN_JVM -> "processResources"
                    else -> "${kotlinTarget.name}ProcessResources"
                }
                tasks.withType<ProcessResources> {
                    if (name == expectedTaskName) {
                        dependsOn(copyLibrariesTask)
                    }
                }
            }
            with(kotlinExtensionDelegate.sourceSets.jvmMain) {
                if (invokedByKotlinJvmBuild) {
                    resources.srcDir(resourceDirectory)
                }
                dependencies {
                    runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                }
            }
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (
            kotlinTarget !is KotlinAndroidTarget
            && cargoBuildVariant.embedRustLibrary.get()
            && cargoBuildVariant.variant == cargoBuildVariant.build.jvmPublishingVariant.get()
            && cargoExtension.publishJvmArtifacts.get()
            && kotlinExtensionDelegate.pluginId == PluginIds.KOTLIN_MULTIPLATFORM
        ) {
            plugins.withId("maven-publish") {
                val publishing = extensions.getByType(PublishingExtension::class.java)
                val publication = publishing.publications.getByName(kotlinTarget.name)
                if (publication is MavenPublication) {
                    publication.artifact(jarTask)
                }
            }
        }

        if (cargoBuildVariant.embedRustLibrary.get()) {
            tasks.named("check") {
                dependsOn(checkTask)
            }
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (androidTarget != null && cargoBuildVariant.androidUnitTest.get()) {
            DependencyUtils.addAndroidUnitTestRuntimeRustLibraryJar(
                this,
                cargoBuildVariant.rustTarget,
                cargoBuildVariant.variant,
                jarTask,
            )
            with(kotlinExtensionDelegate.sourceSets.androidUnitTest(cargoBuildVariant.variant)) {
                dependencies {
                    runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                }
            }
            // Only support debug mode Compose previews.
            // Since one of the dependencies of Compose previews, androidx.compose.ui:ui-tooling,
            // is referenced as debugImplementation in the default template generated from
            // Android Studio, and there is relatively small chance of users requiring to use
            // the Rust library from release mode Compose previews, let's just handle debug mode
            // Compose previews. See #94 for details.
            if (cargoBuildVariant.variant == Variant.Debug
                && cargoBuildVariant.variant == GradleUtils.getComposePreviewVariant(gradle)
            ) {
                with(kotlinExtensionDelegate.sourceSets.androidMain(Variant.Debug)) {
                    dependencies {
                        runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                    }
                }
            }
        }
    }

    private fun Project.configureAndroidPostBuildTasks(cargoBuildVariant: CargoAndroidBuildVariant) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider
        val findDynamicLibrariesTask = cargoBuildVariant.findDynamicLibrariesTaskProvider
        cargoBuildVariant.dynamicLibrarySearchPaths.add(
            cargoBuildVariant.profile.zip(cargoExtension.cargoPackage) { profile, cargoPackage ->
                cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget).asFile
            }
        )
        cargoBuildVariant.dynamicLibrarySearchPaths.addAll(
            cargoBuildVariant.buildTaskProvider.flatMap { it.buildScriptOutputDirectories }
        )
        val projectLayout = layout
        findDynamicLibrariesTask.configure {
            libraryPathsCacheFile.set(projectLayout.outputCacheFile(this, "libraryPathsCacheFile"))
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (!cargoExtension.androidTargetsToBuild.get().contains(cargoBuildVariant.rustTarget))
            return

        if (!cargoBuildVariant.embedRustLibrary.get())
            return

        val copyDestination =
            layout.buildDirectory.dir("intermediates/rust/${cargoBuildVariant.rustTarget.rustTriple}/${cargoBuildVariant.variant}")

        val copyTask = tasks.register<Copy>({
            +"android"
            +cargoBuildVariant
        }) {
            group = TASK_GROUP
            from(
                buildTask.flatMap { task -> task.libraryFileByCrateType.map { it[CrateType.SystemDynamicLibrary]!! } },
                findDynamicLibrariesTask.flatMap { it.libraryPaths },
            )
            into(copyDestination.map { it.dir(cargoBuildVariant.rustTarget.androidAbiName) })
            dependsOn(buildTask, findDynamicLibrariesTask)
        }

        tasks.withType<Jar> {
            if (name.lowercase().contains("android") && variant != null) {
                if (cargoBuildVariant.variant == variant!!) {
                    inputs.dir(copyDestination)
                    dependsOn(copyTask)
                }
            }
        }

        @OptIn(InternalGobleyGradleApi::class)
        androidDelegate.addMainJniDir(this, cargoBuildVariant.variant, copyTask, copyDestination)

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    private fun Project.configureNativeCompilation(
        kotlinTarget: KotlinNativeTarget,
        cargoBuildVariant: CargoNativeBuildVariant<*>,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider

        val buildOutputFile = buildTask
            .flatMap { it.libraryFileByCrateType }
            .map { it[CrateType.SystemStaticLibrary]!! }

        kotlinTarget.compilations.getByName("main") {
            cinterops.register("rust") {
                defFile(buildTask.flatMap { it.nativeStaticLibsDefFile })
                extraOpts(
                    "-libraryPath",
                    cargoExtension.cargoPackage.zip(cargoBuildVariant.profile) { cargoPackage, profile ->
                        cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget)
                    }.get()
                )
                project.tasks.named(interopProcessingTaskName) {
                    inputs.file(buildOutputFile)
                    dependsOn(buildTask)
                }
            }
            compileTaskProvider.configure {
                compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    private fun Project.configureWasmCompilation(
        kotlinTarget: KotlinJsIrTarget,
        cargoBuildVariant: CargoWasmBuildVariant,
        wasmBindgenInstallTask: TaskProvider<InstallWasmTransformerTask>,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider

        cargoBuildVariant.transformWasmProvider.configure {
            wasmTransformer.set(wasmBindgenInstallTask.get().wasmTransformer)
        }

        if (!cargoBuildVariant.embedRustLibrary.get())
            return

        @OptIn(InternalGobleyGradleApi::class)
        kotlinExtensionDelegate.sourceSets.run {
            jsMain.kotlin.srcDir(
                cargoBuildVariant.transformWasmProvider.flatMap { it.outputDirectory }
            )
        }

        kotlinTarget.compilations.getByName("main") {
            compileTaskProvider.dependsOn(buildTask)
        }

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    /**
     * T0.C.6: configure a Kotlin/Wasm wasmJs target on top of an existing
     * CargoWasmBuild. Reuses configureWasmCompilation Kotlin JS hookup
     * for the cdylib bytes plus the Kotlin file emitted by gobley-wasm-transformer,
     * then layers on the per-crate .mjs JS bridge needed by the Kotlin/Wasm
     * bindgen output (see crates/gobley-uniffi-bindgen/src/templates/wasm-js).
     *
     * Wiring summary per Kotlin/Wasm target:
     *   1. Set wasmJsHelpersOutputDirectory on the shared transformWasm task
     *      so it runs the KotlinWasmJsHelpersRenderer and emits
     *      gobley_<crate>_wasmjs_helpers.mjs.
     *   2. Add the directory to the wasmJsMain resources source set so
     *      compileWasmJsMainKotlinWasmJs and the wasm distribution task bundle it
     *      into the runtime artifact.
     *   3. Hook compileTaskProvider to depend on the Cargo build so the Rust
     *      cdylib is up to date before Kotlin compilation; mirrors the JS path.
     */
    @OptIn(InternalGobleyGradleApi::class)
    private fun Project.configureWasmJsCompilation(
        kotlinTarget: KotlinJsIrTarget,
        cargoBuildVariant: CargoWasmBuildVariant,
        wasmBindgenInstallTask: TaskProvider<InstallWasmTransformerTask>,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider
        val isWasmJs = kotlinTarget.wasmTargetType == KotlinWasmTargetType.JS
        if (!isWasmJs) {
            // T0.B Decision 5: wasmWasi support is deferred. The cdylib build
            // still happens (so users can consume the .wasm bytes manually) but
            // we do not auto-wire bindings into wasmWasiMain.
            logger.warn(
                "Kotlin/Wasm target " + kotlinTarget.name + " (wasmTargetType=" +
                    kotlinTarget.wasmTargetType + ") is built by Cargo but Gobley does " +
                    "not yet wire bindings for non-JS Kotlin/Wasm targets " +
                    "(see T0.B Decision 5: wasmWasi deferred)."
            )
            return
        }

        val mjsOutputDir = layout.buildDirectory
            .dir("generated/cargo-wasm-transformation")
            .zip(cargoBuildVariant.profile) { dir, profile ->
                dir
                    .dir(cargoBuildVariant.rustTarget.rustTriple)
                    .dir(profile.targetChildDirectoryName)
                    .dir("wasmjs-helpers")
            }
        // T0.C.6.b: Kotlin/Wasm-flavored Kotlin output. Lives in a
        // sibling root (`cargo-wasm-transformation-wasmjs/`) rather
        // than inside `cargo-wasm-transformation/` so the Kotlin/JS
        // source set's recursive scan of [outputDirectory] does not
        // pick it up — the Kotlin/JS file uses `kotlin.Any` /
        // `org.khronos.webgl.ArrayBuffer` / nested classes that
        // Kotlin/Wasm rejects, and vice versa for the wasmJs file.
        val wasmJsKotlinOutputDir = layout.buildDirectory
            .dir("generated/cargo-wasm-transformation-wasmjs")
            .zip(cargoBuildVariant.profile) { dir, profile ->
                dir
                    .dir(cargoBuildVariant.rustTarget.rustTriple)
                    .dir(profile.targetChildDirectoryName)
            }
        cargoBuildVariant.transformWasmProvider.configure {
            wasmTransformer.set(wasmBindgenInstallTask.get().wasmTransformer)
            wasmJsHelpersOutputDirectory.set(mjsOutputDir)
            wasmJsKotlinOutputDirectory.set(wasmJsKotlinOutputDir)
        }

        if (!cargoBuildVariant.embedRustLibrary.get())
            return

        kotlinExtensionDelegate.sourceSets.run {
            // T0.C.6.b: wasmJs source set picks up the dedicated Kotlin/Wasm
            // file emitted by `KotlinWasmJsRenderer`, NOT the shared
            // `outputDirectory` which holds the Kotlin/JS file. The Kotlin/JS
            // file would fail to compile under Kotlin/Wasm 2.1.10 (200+
            // errors per T0.C.7.C) — use the wasmJs-flavored sibling instead.
            wasmJsMain.kotlin.srcDir(
                cargoBuildVariant.transformWasmProvider.flatMap {
                    it.wasmJsKotlinOutputDirectory
                }
            )
            wasmJsMain.resources.srcDir(
                cargoBuildVariant.transformWasmProvider.flatMap {
                    it.wasmJsHelpersOutputDirectory
                }
            )
        }

        val transformTask = cargoBuildVariant.transformWasmProvider

        kotlinTarget.compilations.getByName("main") {
            compileTaskProvider.dependsOn(buildTask)
            compileTaskProvider.dependsOn(transformTask)
        }

        tasks.matching { it.name == "wasmJsProcessResources" }.configureEach {
            dependsOn(transformTask)
        }

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    private fun Project.configureCleanTasks() {
        val cleanCrate = tasks.register<CargoCleanTask>("cargoClean") {
            group = TASK_GROUP
            cargoPackage.set(cargoExtension.cargoPackage)
        }

        tasks.named<Delete>("clean") {
            dependsOn(cleanCrate)
        }
    }
}

private fun KotlinPlatformType.requiredCrateType(): CrateType? = when (this) {
    // TODO: properly handle JS and WASM targets
    KotlinPlatformType.common -> null
    KotlinPlatformType.jvm -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.js -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.androidJvm -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.native -> CrateType.SystemStaticLibrary
    // T0.C.6: Kotlin/Wasm consumes a wasm32-unknown-unknown cdylib, same
    // as Kotlin/JS. wasm-as-static-library was a placeholder pre-T0.C.
    KotlinPlatformType.wasm -> CrateType.SystemDynamicLibrary
}

private fun ProjectLayout.outputCacheFile(task: Task, propertyName: String): Provider<RegularFile> {
    val trimmedPropertyName = propertyName
        .substringBeforeLast("File")
        .substringBeforeLast("Cache")
    return buildDirectory.file("taskOutputCache/${task.name}/$trimmedPropertyName")
}
