/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.system


enum class OsFamily(val value: String) {
    Windows("windows"),
    Linux("linux"),
    MacOs("macos"),
    FreeBSD("freebsd"),
    Solaris("sunos");

    companion object {
        val current by lazy {
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.startsWith("windows") -> Windows
                osName.startsWith("mac") -> MacOs
                osName.startsWith("linux") -> Linux
                osName.startsWith("freebsd") -> FreeBSD
                osName.startsWith("sunos") -> Solaris
                else -> error("Could not determine OS family")
            }
        }
    }

    val isUnix by lazy { this != Windows }

    val isLinux by lazy { this == Linux }

    val isMac by lazy { this == MacOs }

    val isWindows by lazy { this == Windows }

    val isFileSystemCaseSensitive: Boolean get() = isUnix && this != MacOs
}

enum class Arch(val value: String) {
    X64("x64"),
    Arm64("arm64")
}

interface SystemInfo {

    data class Os(val family: OsFamily, val version: String, val arch: Arch) {
        val familyArch get() = "${family.value.lowercase()}-${arch.value.lowercase()}"
    }

    fun detect(): Os {
        val osName = System.getProperty("os.name")
        var version = System.getProperty("os.version").lowercase()

        if (osName.startsWith("Windows") && osName.matches("Windows \\d+".toRegex())) {
            // for whatever reason, JRE reports "Windows 11" as a name and "10.0" as a version on Windows 11
            try {
                val version2 = osName.substring("Windows".length + 1) + ".0"
                if (version2.toFloat() > version.toFloat()) {
                    version = version2
                }
            } catch (ignored: NumberFormatException) {
            }
        }

        val rawArch = System.getProperty("os.arch").lowercase()
        val arch = if (rawArch.startsWith("aarch64")) Arch.Arm64 else Arch.X64

        return Os(OsFamily.current, version, arch)
    }
}

object DefaultSystemInfo : SystemInfo