/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

class RunAndroidExternalProjectsStandalone : AndroidBaseTest() {

    @Test
    fun kmptexterAppTest() = testRunnerStandalone(
        projectName = "kmptxter",
        applicationId = "com.river.kmptxter"
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