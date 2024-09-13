/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.compose.resources

import java.io.File

internal fun File.listNotHiddenFiles(): List<File> =
    listFiles()?.filter { !it.isHidden }.orEmpty()
