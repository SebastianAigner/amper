/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.amper.core.messages.BuildProblemId
import org.jetbrains.amper.core.messages.Level
import org.jetbrains.amper.core.messages.ProblemReporterContext
import org.jetbrains.amper.frontend.PotatoModule
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.messages.PsiBuildProblem
import org.jetbrains.amper.frontend.messages.extractPsiElement
import org.jetbrains.amper.frontend.messages.extractPsiElementOrNull
import org.jetbrains.amper.frontend.schema.AndroidSettings
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.reader
import kotlin.reflect.KProperty0

abstract class SigningConfigurationIncorrect : AomSingleModuleDiagnosticFactory {
    context(ProblemReporterContext)
    override fun PotatoModule.analyze() {
        this.source.moduleDir?.let { moduleDir ->
            fragments.filter { !it.isTest }.forEach { fragment ->
                val android = fragment.settings.android
                val signing = android.signing
                if (signing.enabled) {
                    analyze(moduleDir, android)
                }
            }
        }
    }

    protected abstract fun ProblemReporterContext.analyze(moduleDir: Path, android: AndroidSettings)

    protected val AndroidSettings.targetProperty: KProperty0<*>
        get() {
            val psiElement = signing.extractPsiElement()
            val shortForm = if (psiElement.children.size == 1) {
                psiElement.children.first().text == "enabled"
            } else {
                false
            }
            val targetProperty: KProperty0<*> = if (shortForm) {
                this::signing
            } else {
                signing::propertiesFile.extractPsiElementOrNull()?.let {
                    signing::propertiesFile
                } ?: this::signing
            }
            return targetProperty
        }
}

object SigningEnabledWithoutPropertiesFileFactory : SigningConfigurationIncorrect() {
    override fun ProblemReporterContext.analyze(
        moduleDir: Path, android: AndroidSettings
    ) {
        val signing = android.signing
        val propertiesFile = moduleDir / signing.propertiesFile
        if (propertiesFile.notExists()) {
            problemReporter.reportMessage(
                SigningEnabledWithoutPropertiesFile(
                    android.targetProperty, propertiesFile
                )
            )
        }
    }

    override val diagnosticId: BuildProblemId = "keystore.properties.does.not.exist"
}

object KeystorePropertiesDoesNotContainKeyFactory : SigningConfigurationIncorrect() {
    override fun ProblemReporterContext.analyze(
        moduleDir: Path, android: AndroidSettings
    ) {
        val signing = android.signing
        val propertiesFile = moduleDir / signing.propertiesFile
        if (propertiesFile.exists()) {
            val properties = Properties()
            propertiesFile.reader().use { properties.load(it) }
            val keys = setOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            for (key in keys) {
                if (!properties.containsKey(key)) {
                    problemReporter.reportMessage(
                        KeystorePropertiesDoesNotContainKey(
                            android.targetProperty, key, propertiesFile
                        )
                    )
                }
            }
        }
    }

    override val diagnosticId: BuildProblemId = "keystore.properties.key.does.not.exist"
}

object MandatoryFieldInPropertiesFileMustBePresentFactory : SigningConfigurationIncorrect() {
    override fun ProblemReporterContext.analyze(
        moduleDir: Path, android: AndroidSettings
    ) {
        val signing = android.signing
        val propertiesFile = moduleDir / signing.propertiesFile
        if (propertiesFile.exists()) {
            val properties = Properties()
            propertiesFile.reader().use { properties.load(it) }
            val mandatoryFields = setOf("storeFile", "keyAlias")
            for (key in mandatoryFields) {
                val value = properties.getProperty(key)
                if (value.isNullOrBlank()) {
                    problemReporter.reportMessage(
                        MandatoryFieldInPropertiesFileMustBePresent(
                            android.targetProperty, key, propertiesFile
                        )
                    )
                }
            }
        }
    }

    override val diagnosticId: BuildProblemId = "keystore.properties.value.required"
}

object KeystoreMustExistFactory : SigningConfigurationIncorrect() {
    override fun ProblemReporterContext.analyze(
        moduleDir: Path, android: AndroidSettings
    ) {
        val signing = android.signing
        val propertiesFile = moduleDir / signing.propertiesFile
        if (propertiesFile.exists()) {
            val properties = Properties()
            propertiesFile.reader().use { properties.load(it) }
            if (properties.containsKey("storeFile")) {
                val storeFile = properties.getProperty("storeFile")
                if (Path(storeFile).notExists()) {
                    problemReporter.reportMessage(
                        KeystoreFileDoesNotExist(
                            android.targetProperty, Path(storeFile)
                        )
                    )
                }
            }

        }
    }

    override val diagnosticId: BuildProblemId = "keystore.properties.file.does.not.exist"
}

class SigningEnabledWithoutPropertiesFile(
    val targetProperty: KProperty0<*>, val propertiesFilePath: Path
) : PsiBuildProblem(Level.Warning) {
    override val element: PsiElement get() = targetProperty.extractPsiElement()
    override val buildProblemId: BuildProblemId = SigningEnabledWithoutPropertiesFileFactory.diagnosticId
    override val message: String
        get() = SchemaBundle.message(
            messageKey = buildProblemId, propertiesFilePath
        )
}

class KeystorePropertiesDoesNotContainKey(
    val targetProperty: KProperty0<*>,
    val key: String,
    val propertiesFilePath: Path,
) : PsiBuildProblem(Level.Warning) {
    override val element: PsiElement get() = targetProperty.extractPsiElement()
    override val buildProblemId: BuildProblemId = KeystorePropertiesDoesNotContainKeyFactory.diagnosticId
    override val message: String
        get() = SchemaBundle.message(
            messageKey = buildProblemId, propertiesFilePath, key
        )
}

class MandatoryFieldInPropertiesFileMustBePresent(
    val targetProperty: KProperty0<*>, val key: String, val propertiesFilePath: Path
) : PsiBuildProblem(Level.Warning) {
    override val element: PsiElement get() = targetProperty.extractPsiElement()
    override val buildProblemId: BuildProblemId = MandatoryFieldInPropertiesFileMustBePresentFactory.diagnosticId
    override val message: String
        get() = SchemaBundle.message(
            messageKey = buildProblemId, propertiesFilePath, key
        )
}

class KeystoreFileDoesNotExist(
    val targetProperty: KProperty0<*>, val keystorePath: Path
) : PsiBuildProblem(Level.Warning) {
    override val element: PsiElement get() = targetProperty.extractPsiElement()
    override val buildProblemId: BuildProblemId = KeystoreMustExistFactory.diagnosticId
    override val message: String
        get() = SchemaBundle.message(
            messageKey = buildProblemId, keystorePath
        )
}