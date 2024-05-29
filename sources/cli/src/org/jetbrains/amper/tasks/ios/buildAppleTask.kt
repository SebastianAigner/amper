/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.diagnostics.setAmperModule
import org.jetbrains.amper.diagnostics.setListAttribute
import org.jetbrains.amper.diagnostics.spanBuilder
import org.jetbrains.amper.diagnostics.useWithScope
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.PotatoModule
import org.jetbrains.amper.frontend.forClosure
import org.jetbrains.amper.frontend.schema.Settings
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.tasks.BuildTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.native.NativeCompileTask
import org.jetbrains.amper.util.BuildType
import org.jetbrains.amper.util.ExecuteOnChangedInputs
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString


class BuildAppleTask(
    override val platform: Platform,
    override val module: PotatoModule,
    private val buildType: BuildType,
    private val executeOnChangedInputs: ExecuteOnChangedInputs,
    private val taskOutputPath: TaskOutputRoot,
    private val terminal: Terminal,
    override val taskName: TaskName,
    override val isTest: Boolean,
) : BuildTask {
    private val prettyPlatform = platform.pretty

    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val nativeCompileTasksResults = dependenciesResult
            .filterIsInstance<NativeCompileTask.TaskResult>()
            .map { it.artifact }

        val leafAppleFragment = module.leafFragments.first { it.platform == platform }
        val targetName = prettyPlatform
        val productName = module.userReadableName
        val productBundleIdentifier = getQualifiedName(leafAppleFragment.settings, targetName, productName)

        // Define required apple sources.
        val currentPlatformFamily = platform.parent?.let { it.leaves + it } ?: emptySet()
        val appleSources = buildSet {
            leafAppleFragment.forClosure {
                if (currentPlatformFamily.containsAll(it.platforms)) add(it.src.toFile().normalize())
            }
        }

        // TODO Add Assertion for apple platform.
        return with(FileConventions(module, taskOutputPath.path.toFile())) {
            val appPath = symRoot
                .resolve("${buildType.variantName}-${platform.platform}")
                .resolve("${productName}.app")

            // TODO Add all other ios settings.
            val config = mapOf(
                "target.platform" to prettyPlatform,
                "task.output.root" to taskOutputPath.path.pathString,
                "build.type" to buildType.value,
            )

            executeOnChangedInputs.execute(
                taskName.name,
                config,
                appleSources.map { it.toPath() } + nativeCompileTasksResults,
            ) {
                logger.info("Generating xcode project")
                doGenerateBuildableXcodeproj(
                    module,
                    leafAppleFragment,
                    targetName,
                    productName,
                    productBundleIdentifier,
                    buildType,
                    appleSources,
                    nativeCompileTasksResults.map { it.toFile() },
                )

                val xcodebuildArgs = buildList {
                    this += "xcrun"
                    this += "xcodebuild"
                    this += "-project"; this += projectDir.path
                    this += "-scheme"; this += targetName
                    this += "-configuration"; this += "Debug"
                    this += "${BuildSettingNames.OBJROOT}=$objRootPathString"
                    this += "${BuildSettingNames.SYMROOT}=$symRootPathString"
                    this += "-arch"; this += platform.architecture
                    this += "-derivedDataPath"; this += derivedDataPathString
                    this += "-sdk"; this += platform.platform
                    this += "build"
                }
                spanBuilder("xcodebuild")
                    .setAmperModule(module)
                    .setListAttribute("args", xcodebuildArgs)
                    .useWithScope { span ->
                        // TODO Maybe we dont need output here?
                        BuildPrimitives.runProcessAndGetOutput(
                            baseDir.toPath(),
                            *xcodebuildArgs.toTypedArray(),
                            span = span,
                            logCall = true,
                            outputListener = LoggingProcessOutputListener(logger),
                        )
                    }

                return@execute ExecuteOnChangedInputs.ExecutionResult(listOf(appPath.toPath()))
            }

            Result(
                productBundleIdentifier,
                appPath.toPath()
            )
        }
    }

    class Result(
        val bundleId: String,
        val appPath: Path,
    ) : TaskResult {
        override val dependencies = emptyList<TaskResult>()
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}

private fun getQualifiedName(
    settings: Settings,
    targetName: String,
    productName: String,
): String = listOfNotNull(
    settings.publishing?.group?.takeIf { it.isNotBlank() },
    targetName,
    productName.takeIf { it.isNotBlank() }
).joinToString(".")
