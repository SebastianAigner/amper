/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schemaConverter.psi

import com.intellij.psi.PsiElement
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.applyPsiTrace
import org.jetbrains.amper.frontend.api.asTraceable
import org.jetbrains.amper.frontend.api.valueBase
import org.jetbrains.amper.frontend.schema.CatalogDependency
import org.jetbrains.amper.frontend.schema.CatalogKey
import org.jetbrains.amper.frontend.schema.CatalogKspProcessorDeclaration
import org.jetbrains.amper.frontend.schema.Dependency
import org.jetbrains.amper.frontend.schema.DependencyScope
import org.jetbrains.amper.frontend.schema.ExternalMavenDependency
import org.jetbrains.amper.frontend.schema.InternalDependency
import org.jetbrains.amper.frontend.schema.MavenKspProcessorDeclaration
import org.jetbrains.amper.frontend.schema.ModuleKspProcessorDeclaration
import org.jetbrains.yaml.YAMLLanguage

context(Converter)
internal fun instantiateKspProcessor(
    scalarValue: Scalar?
): Any? {
    val text = scalarValue?.textValue ?: return null
    return when {
        text.startsWith("$") -> CatalogKspProcessorDeclaration(CatalogKey(text.substring(1)).applyPsiTrace(scalarValue.sourceElement))
        text.startsWith(".") -> ModuleKspProcessorDeclaration(text.asAbsolutePath().asTraceable().applyPsiTrace(scalarValue.sourceElement))
        else -> MavenKspProcessorDeclaration(TraceableString(text).applyPsiTrace(scalarValue.sourceElement))
    }
}

context(Converter)
internal fun instantiateDependency(
    scalarValue: Scalar?,
    applicableKeys: List<KeyWithContext>,
    path: Pointer,
    table: Map<KeyWithContext, AmperElementWrapper>,
    contexts: Set<TraceableString>
): Any? {
    val textValue = scalarValue?.textValue
    if ((scalarValue?.sourceElement?.language is YAMLLanguage
                || textValue == path.segmentName) && textValue != null) {
        return instantiateDependency(textValue, scalarValue.sourceElement).also { dep ->
            readFromTable(dep, table, path, contexts)
        }
    } else {
        val matchingKeys = applicableKeys.filter { it.key.startsWith(path) }.let {
            if (it.size > 1) it.filter { it.key != path } else it
        }
        if (matchingKeys.size == 1) {
            val key = matchingKeys.single()
            val sourceElement = table[key]?.sourceElement
            val specialValue = (table[key] as? Scalar)?.textValue
            val segmentName = key.key.segmentName
            if (specialValue != null && segmentName != null) {
                instantiateDependency(segmentName, sourceElement).also { dep ->
                    if (specialValue == "exported") {
                        dep.exported = true
                        dep::exported.valueBase?.doApplyPsiTrace(sourceElement)
                        return dep
                    } else {
                        DependencyScope[specialValue]?.let {
                            dep.scope = it
                            dep::scope.valueBase?.doApplyPsiTrace(sourceElement)
                            return dep
                        }
                    }
                }
            }
        }
        else {
            val pointer = if (path.segmentName?.toIntOrNull() != null) {
                matchingKeys.map {
                    it.key.nextAfter(path)
                }.distinct().singleOrNull()
            } else path
            if (pointer != null) {
                val sourceElement = table[KeyWithContext(pointer, contexts)]?.sourceElement
                return instantiateDependency(pointer.segmentName!!, sourceElement).also { dep ->
                    readFromTable(dep, table, pointer, contexts)
                }
            }
        }
    }
    return null
}

context(Converter)
internal fun instantiateDependency(text: String, sourceElement: PsiElement?): Dependency {
    return when {
        text.startsWith(".") -> InternalDependency().also {
            it.path = text.asAbsolutePath()
            it::path.valueBase?.doApplyPsiTrace(sourceElement)
        }
        text.startsWith("$") -> CatalogDependency().also {
            it.catalogKey = CatalogKey(text.substring(1)).applyPsiTrace(sourceElement)
            it::catalogKey.valueBase?.doApplyPsiTrace(sourceElement)
        }
        else -> ExternalMavenDependency().also {
            it.coordinates = text
            it::coordinates.valueBase?.doApplyPsiTrace(sourceElement)
        }
    }.also { it.doApplyPsiTrace(sourceElement) }
}