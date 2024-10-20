/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.jetbrains.amper.core.AmperBuild
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Helpers to run the locally published Amper CLI distribution.
 */
object LocalAmperPublication {
    private val rootPublicationDir = TestUtil.m2repository.resolve("org/jetbrains/amper/cli/${AmperBuild.mavenVersion}")

    val distZip: Path = rootPublicationDir.resolve("cli-${AmperBuild.mavenVersion}-dist.zip")
    val wrapperBat: Path = rootPublicationDir.resolve("cli-${AmperBuild.mavenVersion}-wrapper.bat")
    val wrapperSh: Path = rootPublicationDir.resolve("cli-${AmperBuild.mavenVersion}-wrapper")

    private fun checkPublicationIntegrity() {
        val explanation = "This test requires the locally-published Amper CLI distribution and wrappers.\n" +
                "Make sure you have setup this test task to depend on the publication tasks to ensure that."
        check(distZip.exists()) { "Amper distribution is missing in maven local: $distZip not found.\n$explanation" }
        check(wrapperBat.exists()) { "Amper wrapper.bat is missing in maven local: $wrapperBat not found.\n$explanation" }
        check(wrapperSh.exists()) { "Amper wrapper (sh) is missing in maven local: $wrapperSh not found.\n$explanation" }

        assertTrue(wrapperBat.readText().count { it == '\r' } > 10,
            "Windows wrapper must have \\r in line separators: $wrapperBat")

        assertTrue(wrapperSh.readText().count { it == '\r' } == 0,
            "Unix wrapper must not have \\r in line separators: $wrapperSh")
    }

    /**
     * Copies the locally-published CLI scripts (`amper` / `amper.bat`) to the given [targetDir].
     */
    fun setupWrappersIn(targetDir: Path) {
        checkPublicationIntegrity()

        wrapperBat.copyTo(targetDir.resolve("amper.bat"), REPLACE_EXISTING)

        val targetSh = targetDir.resolve("amper")
        wrapperSh.copyTo(targetSh, REPLACE_EXISTING)
        targetSh.toFile().setExecutable(true)
    }
}