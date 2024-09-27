/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.compose.PrepareComposeResourcesTask

/**
 * Just passes through the prepared Compose Resources to be packaged as assets for Android.
 *
 * **Inputs**:
 * - [PrepareComposeResourcesTask.Result]
 *
 * **Output**: [AdditionalAndroidAssetsProvider].
 *
 * @see AndroidAarTask
 */
class AndroidComposeResourcesTask(
    override val taskName: TaskName,
) : Task {
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val prepareResources = dependenciesResult.requireSingleDependency<PrepareComposeResourcesTask.Result>()

        // NOTE: If needed to support split packaging (jvm resources/assets) for before 1.7.0-beta02 -
        //  is the good place to do so.
        return Result(
            assetsRoots = listOf(
                AdditionalAndroidAssetsProvider.AssetsRoot(
                    path = prepareResources.outputDir,
                    relativePackagingPath = prepareResources.relativePackagingPath,
                ),
            ),
        )
    }

    private class Result(
        override val assetsRoots: List<AdditionalAndroidAssetsProvider.AssetsRoot>,
    ) : TaskResult, AdditionalAndroidAssetsProvider
}