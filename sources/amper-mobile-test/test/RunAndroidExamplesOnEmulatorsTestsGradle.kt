/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

class RunAndroidExamplesOnEmulatorsTestsGradle : AndroidBaseTest() {

    @Test
    fun composeAndroidAppGradle() = testRunnerGradle(
        projectName = "compose-android",
    )

    @Test
    fun composeAndroidMultiplatformAppGradle() = testRunnerGradle(
        projectName = "multiplatform",
    )

    @AfterEach
    fun cleanup() {
       val projectFolder = Path("${System.getProperty("user.dir")}/tempProjects")
        projectFolder.deleteRecursively()
        runBlocking {
            deleteAdbRemoteSession()
        }
    }
}