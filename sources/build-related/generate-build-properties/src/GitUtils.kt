import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.security.MessageDigest

/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

/**
 * Runs the provided [block] of code with an empty system and user git config.
 *
 * Any git operation (most importantly [Git.open]) within the given block will disregard the global git config of the machine.
 */
internal fun runWithoutGlobalGitConfig(block: () -> Unit) {
    val oldSystemReader = SystemReader.getInstance()
    try {
        SystemReader.setInstance(EmptyConfigSystemReader(oldSystemReader))
        block()
    } finally {
        SystemReader.setInstance(oldSystemReader)
    }
}

private class EmptyConfigSystemReader(private val delegate: SystemReader = getInstance()) : SystemReader() {
    override fun getHostname(): String? = delegate.hostname
    override fun getenv(variable: String?): String? = delegate.getenv(variable)
    override fun getProperty(key: String?): String? = delegate.getProperty(key)
    override fun getCurrentTime(): Long = delegate.currentTime
    override fun getTimezone(`when`: Long): Int = delegate.getTimezone(`when`)
    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = NoopFileBasedConfig(parent, fs)
    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = NoopFileBasedConfig(parent, fs)
    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = delegate.openJGitConfig(parent, fs)
}

private class NoopFileBasedConfig(parent: Config?, fs: FS?) : FileBasedConfig(parent, null, fs) {
    override fun load() {}
    override fun isOutdated(): Boolean = false
}

/**
 * Returns a hash of the entire local changes diff content.
 */
fun Git.localChangesHash() = diff().call().map { it.newId.toObjectId().name }.hash()

@OptIn(ExperimentalStdlibApi::class)
private fun Iterable<String>.hash(): String {
    val hasher = MessageDigest.getInstance("md5")
    forEach {
        hasher.update(it.encodeToByteArray())
    }
    return hasher.digest().toHexString()
}