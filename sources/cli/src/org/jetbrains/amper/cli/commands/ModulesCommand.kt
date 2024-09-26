/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import org.jetbrains.amper.cli.withBackend

internal class ModulesCommand : SuspendingCliktCommand(name = "modules") {

    val commonOptions by requireObject<RootCommand.CommonOptions>()

    override fun help(context: Context): String = "List all modules in the project"

    override suspend fun run() {
        withBackend(commonOptions, commandName) { backend ->
            backend.showModules()
        }
    }
}