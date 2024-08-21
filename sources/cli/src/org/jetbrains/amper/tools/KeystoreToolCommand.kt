/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import org.jetbrains.amper.android.keystore.KeystoreHelper
import org.jetbrains.amper.cli.RootCommand
import org.jetbrains.amper.cli.withBackend
import kotlin.io.path.Path
import kotlin.io.path.div

class KeystoreToolCommand : CliktCommand(
    name = "generate-keystore",
    help = "Generate keystore",
    epilog = "Use -- to separate tool's arguments from Amper options",
) {

    private val commonOptions by requireObject<RootCommand.CommonOptions>()

    private val propertiesFile by option(
        "--properties-file",
        help = "Path to properties file which is used to populate storeFile, storePassword, keyAlias, keyPassword during the generation"
    ).file()

    private val storeFile by option("--keystore-file", help = "Where to store keystore").file()
        .default((Path(System.getProperty("user.home")) / ".keystores" / "release.keystore").toFile())
    private val storePassword by option("--keystore-password", help = "Keystore password").default("")
    private val keyAlias by option("--key-alias", help = "Key alias").default("android")
    private val keyPassword by option("--key-password", help = "Key password").default("")
    private val dn by option("--dn", help = "issuer").default("CN=${System.getProperty("user.name", "Unknown")}")

    override fun run() {
        withBackend(commonOptions, commandName) {
            propertiesFile?.let { KeystoreHelper.createNewKeystore(it.toPath(), dn) }
                ?: KeystoreHelper.createNewKeystore(storeFile.toPath(), storePassword, keyAlias, keyPassword, dn)
        }
    }
}
