/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.gradle

import com.android.build.gradle.internal.lint.AndroidLintAnalysisTask
import com.android.build.gradle.internal.lint.LintModelWriterTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.jetbrains.amper.core.Result
import org.jetbrains.amper.core.UsedVersions
import org.jetbrains.amper.core.get
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.ModelInit
import org.jetbrains.amper.frontend.aomBuilder.chooseComposeVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import kotlin.io.path.extension

/**
 * Gradle setting plugin, that is responsible for:
 * 1. Initializing gradle projects, based on the Amper model.
 * 2. Applying kotlin or KMP plugins.
 * 3. Associate gradle projects with modules.
 */
// This is registered via FQN from the resources in org.jetbrains.amper.settings.plugin.properties
class BindingSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val rootPath = settings.rootDir.toPath().toAbsolutePath()
        with(SLF4JProblemReporterContext()) {
            val modelResult = ModelInit.getModel(rootPath)
            if (modelResult is Result.Failure<Model> || problemReporter.hasFatal) {
                throw GradleException(problemReporter.getGradleError())
            }

            // Use [ModelWrapper] to cache and preserve links on [PotatoModule].
            val model = ModelWrapper(modelResult.get())

            settings.gradle.knownModel = model

            settings.setupComposePlugin(model)

            initProjects(settings, model)

            // Initialize plugins for each module.
            settings.gradle.beforeProject { project ->
                configureProject(settings, project)
            }
        }
    }

    private fun Settings.setupComposePlugin(model: ModelWrapper) {
        val chosenComposeVersion = chooseComposeVersion(model)
        // We don't need to use the dynamic plugin mechanism if the user wants the embedded Compose version (because
        // it's already on the classpath). Using dynamic plugins relies on unreliable internal Gradle APIs, which are
        // absent in (or incompatible with) recent Gradle versions, so we only use this if absolutely necessary.
        if (chosenComposeVersion != null && chosenComposeVersion != UsedVersions.composeVersion) {
            setupDynamicPlugins(
                "org.jetbrains.compose:compose-gradle-plugin:$chosenComposeVersion",
            ) {
                mavenCentral()
                // For compose dev versions.
                maven { it.setUrl("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
            }
        }
    }

    private fun configureProject(settings: Settings, project: Project) {

        // Dirty hack related with the same problem as here
        // https://github.com/JetBrains/compose-multiplatform/blob/b6e7ba750c54fddfd60c57b0a113d80873aa3992/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/resources/ComposeResources.kt#L75
        listOf(
            "com.android.application",
            "com.android.library"
        ).forEach {
            project.plugins.withId(it) {
                project.tasks.matching {
                    it is AndroidLintAnalysisTask || it is LintModelWriterTask
                }.configureEach { task ->
                    project.tasks.matching { it.name.startsWith("generateResourceAccessorsFor") }
                        .map { it.name }
                        .forEach {
                            task.mustRunAfter(it)
                        }
                }
            }
        }

        // Gradle projects that are not in the map aren't Amper projects (modules) anyway,
        // so we can stop here
        val connectedModule = settings.gradle.projectPathToModule[project.path] ?: run {
            if (project.path == project.rootProject.path) {
                // Add default repositories to the root project, it is required for further applying kmp plugin
                project.repositories.addDefaultAmperRepositoriesForDependencies()
            }
            return
        }
        if (!connectedModule.hasAmperConfigFile()) {
            // we don't want to alter non-Amper subprojects
            return
        }

        // /!\ This overrides any user configuration from settings.gradle.kts
        // This is only done in modules with Amper's module.yaml config to avoid issues
        project.repositories.addDefaultAmperRepositoriesForDependencies()

        // Disable warning about Default Kotlin Hierarchy.
        project.extraProperties.set("kotlin.mpp.applyDefaultHierarchyTemplate", "false")

        // Apply Kotlin plugins.
        project.plugins.apply(KotlinMultiplatformPluginWrapper::class.java)
        project.plugins.apply(BindingProjectPlugin::class.java)

        project.afterEvaluate {
            // W/A for XML factories mess within apple plugin classpath.
            val hasAndroidPlugin = it.plugins.hasPlugin("com.android.application") ||
                    it.plugins.hasPlugin("com.android.library")
            if (hasAndroidPlugin) {
                adjustXmlFactories()
            }
        }
    }
}

private fun PotatoModuleWrapper.hasAmperConfigFile() = buildFile.extension == "yaml"

private fun RepositoryHandler.addDefaultAmperRepositoriesForDependencies() {
    mavenCentral()
    // For the Android plugin and dependencies
    google()
    // For other Gradle plugins
    gradlePluginPortal()
    // For dev versions of kotlin
    maven { it.setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev") }
    // For dev versions of compose plugin and dependencies
    maven { it.setUrl("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}