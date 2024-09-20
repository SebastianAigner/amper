/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.cli.JdkDownloader
import org.jetbrains.amper.cli.RootCommand
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.system.OsFamily
import org.jetbrains.amper.intellij.CommandLineUtils
import kotlin.io.path.isExecutable
import kotlin.io.path.pathString

class JdkToolCommand(private val name: String) : CliktCommand(name = name) {

    private val toolArguments by argument(name = "tool arguments").multiple()
    private val commonOptions by requireObject<RootCommand.CommonOptions>()

    override fun helpEpilog(context: Context): String = "Use -- to separate $name's arguments from Amper options"

    override fun run() = runBlocking {
        val jdk = JdkDownloader.getJdk(commonOptions.sharedCachesRoot)
        val ext = if (OsFamily.current.isWindows) ".exe" else ""
        val toolPath = jdk.javaExecutable.resolveSibling(name + ext)
        if (!toolPath.isExecutable()) {
            userReadableError("Tool is not present or not executable: $toolPath")
        }

        val cmd = listOf(toolPath.pathString) + toolArguments
        val exitCode = ProcessBuilder(CommandLineUtils.quoteCommandLineForCurrentPlatform(cmd))
            .inheritIO()
            .start()
            .awaitExit()
        if (exitCode != 0) {
            userReadableError("$name exited with exit code $exitCode")
        }
    }
}
