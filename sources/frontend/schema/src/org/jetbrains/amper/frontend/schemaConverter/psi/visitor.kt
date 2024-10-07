/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schemaConverter.psi

import com.intellij.amper.lang.AmperContextBlock
import com.intellij.amper.lang.AmperContextName
import com.intellij.amper.lang.AmperContextualElement
import com.intellij.amper.lang.AmperContextualStatement
import com.intellij.amper.lang.AmperElementVisitor
import com.intellij.amper.lang.AmperLanguage
import com.intellij.amper.lang.AmperLiteral
import com.intellij.amper.lang.AmperObject
import com.intellij.amper.lang.AmperProperty
import com.intellij.amper.lang.impl.propertyList
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentsOfType
import com.intellij.util.containers.Stack
import org.jetbrains.amper.core.messages.BuildProblemImpl
import org.jetbrains.amper.core.messages.Level
import org.jetbrains.amper.frontend.SchemaBundle
import org.jetbrains.amper.frontend.SchemaEnum
import org.jetbrains.amper.frontend.api.ImplicitConstructor
import org.jetbrains.amper.frontend.api.ImplicitConstructorParameter
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Traceable
import org.jetbrains.amper.frontend.api.TraceableEnum
import org.jetbrains.amper.frontend.api.TraceablePath
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.ValueBase
import org.jetbrains.amper.frontend.api.applyPsiTrace
import org.jetbrains.amper.frontend.api.valueBase
import org.jetbrains.amper.frontend.builders.collectionType
import org.jetbrains.amper.frontend.builders.isBoolean
import org.jetbrains.amper.frontend.builders.isCollection
import org.jetbrains.amper.frontend.builders.isInt
import org.jetbrains.amper.frontend.builders.isMap
import org.jetbrains.amper.frontend.builders.isPath
import org.jetbrains.amper.frontend.builders.isScalar
import org.jetbrains.amper.frontend.builders.isString
import org.jetbrains.amper.frontend.builders.mapValueType
import org.jetbrains.amper.frontend.builders.schemaDeclaredMemberProperties
import org.jetbrains.amper.frontend.builders.unwrapKClass
import org.jetbrains.amper.frontend.messages.PsiBuildProblemSource
import org.jetbrains.amper.frontend.schema.CatalogDependency
import org.jetbrains.amper.frontend.schema.Dependency
import org.jetbrains.amper.frontend.schema.DependencyScope
import org.jetbrains.amper.frontend.schema.ExternalMavenDependency
import org.jetbrains.amper.frontend.schema.InternalDependency
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YamlPsiElementVisitor
import java.nio.file.Path
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability

internal data class KeyWithContext(val key: Pointer, val contexts: Set<String>)

internal fun PsiElement.readValueTable(): Map<KeyWithContext, AmperElementWrapper> {
    val table = mutableMapOf<KeyWithContext, AmperElementWrapper>()
    object : AmperPsiAdapterVisitor() {
        override fun visitScalar(node: Scalar) {
            val parentEntry = MappingEntry.byKey(node.sourceElement)
            val value = parentEntry?.value
            if (value == null || Scalar.from(value) == null) {
                addNode(node)
            }
            super.visitScalar(node)
        }

        override fun visitMappingEntry(node: MappingEntry) {
            addNode(node)
            super.visitMappingEntry(node)
        }

        override fun visitSequenceItem(item: PsiElement, index: Int) {
            if (!table.containsKey(KeyWithContext(position, context))) {
                addNode(UnknownElementWrapper(item))
            }
            super.visitSequenceItem(item, index)
        }

        private fun addNode(node: AmperElementWrapper) {
            table[KeyWithContext(position, context)] = node
        }
    }.visitElement(this)
    return table
}

class Pointer(val segmentName: String? = null,
                       var prev: Pointer? = null,
                       var next: Pointer? = null) {
    private val firstSegment get() = run {
        var p: Pointer = this
        while (p.prev != null) p = p.prev!!
        p
    }

    operator fun plus(value: String): Pointer {
        if (segmentName == null && prev == null && next == null) {
            return Pointer(value)
        }

        val copy = deepCopyWithPrev()
        val newPointer = Pointer(value, copy)
        copy.next = newPointer
        return newPointer
    }

    private fun deepCopyWithPrev(): Pointer {
        val prevCopy = prev?.deepCopyWithPrev()
        val copy = Pointer(segmentName, prevCopy)
        prevCopy?.next = copy
        return copy
    }

    fun nextAfter(o: Pointer): Pointer? {
        var own: Pointer? = firstSegment
        var other: Pointer? = o.firstSegment
        while (other != null) {
            if (own == null || own.segmentName != other.segmentName) return null
            if (own == this || other == o) break
            own = own.next
            other = other.next
        }
        return own?.next
    }

    fun startsWith(o: Pointer): Boolean {
        var own: Pointer? = firstSegment
        var other: Pointer? = o.firstSegment
        while (other != null) {
            if (own == null || own.segmentName != other.segmentName) return false
            if (other == o) break
            if (own == this) {
                return other.next == null
            }
            own = own.next
            other = other.next
        }
        return true
    }

    override fun hashCode(): Int {
        return toString().hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Pointer || segmentName != other.segmentName) return false
        return toString() == other.toString()
    }

    override fun toString(): String {
        val builder = StringBuilder()
        val firstSegment1 = firstSegment
        var p: Pointer? = firstSegment1
        while (p != null) {
            if (p !== firstSegment1) builder.append(" :: ")
            builder.append(p.segmentName)
            if (p === this) break
            p = p.next
        }
        return builder.toString()
    }
}

context(Converter)
private fun instantiateDependency(text: String): Dependency {
    return when {
        text.startsWith(".") -> InternalDependency().also { it.path = text.asAbsolutePath() }
        text.startsWith("$") -> CatalogDependency().also { it.catalogKey = text.substring(1) }
        else -> ExternalMavenDependency().also { it.coordinates = text }
    }
}

context(Converter)
internal fun readTypedValue(
    type: KType,
    table: Map<KeyWithContext, AmperElementWrapper>,
    path: Pointer,
    contexts: Set<String>,
    valueBase: ValueBase<Any?>? = null
): Any? {
    if (type.withNullability(false) != type) {
        return readTypedValue(type.withNullability(false), table, path, contexts, valueBase)
    }
    if (type.isSubtypeOf(TraceableEnum::class.starProjectedType)
        || type.isSubtypeOf(TraceableString::class.starProjectedType)
        || type.isSubtypeOf(TraceablePath::class.starProjectedType)) {
        val scalarValue = table[KeyWithContext(path, contexts)]
        return instantiateTraceableScalar(type, table, path, contexts, valueBase, scalarValue)
    }
    val applicableKeys = table.keys.filter { it.key.startsWith(path) && it.contexts.containsAll(contexts) }
    if (applicableKeys.isEmpty()) return null

    if (type.isScalar) {
        val scalarValue = table[KeyWithContext(path, contexts)] as? Scalar
        val text = scalarValue?.textValue ?: return null
        return convertScalarType(type, scalarValue, text, valueBase)
    }
    if (type.isMap) {
        if (type.arguments[0].type?.isCollection == true) {
            return applicableKeys.map { it.contexts }
                .associate { ks -> ks.map { TraceableString(it) }.toSet() to readTypedValue(
                    type.mapValueType,
                    table,
                    path,
                    ks
                ) }
        }
        val processedKeys = mutableSetOf<String>()
        return applicableKeys.mapNotNull {
            val key = it.key.nextAfter(path)?.let {
                // hack for yaml
                if (it.segmentName?.toIntOrNull() != null) it.next else it
            }
            val name = key?.segmentName
            if (name == null || it.key.prev == null || !processedKeys.add(name)) null
            else (name to readTypedValue(type.mapValueType, table, key, contexts))
        }.toMap()
    }
    if (type.isCollection)  {
        val visitedKeys = hashSetOf<Pointer>()
        return applicableKeys.mapNotNull { keyWithContext ->
            val newKey = keyWithContext.key.nextAfter(path)
            if (newKey != null && !visitedKeys.any { newKey.startsWith(it) }) {
                visitedKeys.add(newKey)
                readTypedValue(type.collectionType, table, newKey, contexts)
            } else null
        }.let { if (type.isSubtypeOf(Set::class.starProjectedType)) it.toSet() else it }
    }

    if (type.isSubtypeOf(SchemaNode::class.starProjectedType)) {
        val scalarValue = table[KeyWithContext(path, contexts)] as? Scalar
        val textValue = scalarValue?.textValue

        // hack for internal and catalog dependencies, need to rethink this
        if (type.unwrapKClass == Dependency::class) {
            return instantiateDependency(scalarValue, applicableKeys, path, table, contexts)
        }

        if (textValue != null) {
            // find the implicit constructor and invoke it
            val constructedType = type.unwrapKClass.findAnnotation<ImplicitConstructor>()?.constructedType
                ?: type.unwrapKClass

            val props = constructedType.schemaDeclaredMemberProperties()
                .filterIsInstance<KMutableProperty1<Any, Any?>>()
            val param = props.singleOrNull {
                    it.hasAnnotation<ImplicitConstructorParameter>()
                } ?: props.singleOrNull()
                  // "enabled" shortcut
                  ?: props.singleOrNull { it.name == "enabled" }?.takeIf { textValue == "enabled" }

            if (param != null) {
                return type.instantiateType().also {
                    val value = if (param.name == "enabled" && textValue == "enabled") "true" else textValue
                    val convertedValue = convertScalarType(
                        param.returnType, scalarValue,
                        value, param.valueBase(it)
                    )
                    setPropertyValueSafe(param, it, convertedValue)
                    if (it is Traceable) {
                        it.doApplyPsiTrace(scalarValue.sourceElement)
                    }
                }
            }
        }
    }

    return type.instantiateType().also { instance ->
        if (instance is Traceable) {
            table[KeyWithContext(path, contexts)]?.sourceElement?.let {
                instance.doApplyPsiTrace(it)
            }
        }
        readFromTable(instance, table, path, contexts)
    }
}

private fun setPropertyValueSafe(
    param: KMutableProperty1<Any, Any?>,
    target: Any,
    convertedValue: Any?
) {
    // todo maybe report this
    if (convertedValue == null && !param.returnType.isMarkedNullable) return
    param.set(target, convertedValue)
}

context(Converter)
private fun instantiateTraceableScalar(
    type: KType,
    table: Map<KeyWithContext, AmperElementWrapper>,
    path: Pointer,
    contexts: Set<String>,
    valueBase: ValueBase<Any?>?,
    scalarValue: AmperElementWrapper?
): Traceable? {
    return when (type.unwrapKClass) {
        TraceableEnum::class -> type.arguments[0].type?.let {
            readTypedValue(
                it,
                table,
                path,
                contexts,
                valueBase
            )
        }?.let {
            TraceableEnum::class.primaryConstructor!!.call(it).doApplyPsiTrace(scalarValue?.sourceElement)
        }

        TraceableString::class -> readTypedValue(
            String::class.starProjectedType,
            table,
            path,
            contexts,
            valueBase
        )?.let {
            TraceableString(it as String).doApplyPsiTrace(scalarValue?.sourceElement)
        }

        TraceablePath::class -> readTypedValue(
            Path::class.starProjectedType,
            table,
            path,
            contexts,
            valueBase
        )?.let {
            TraceablePath(it as Path).doApplyPsiTrace(scalarValue?.sourceElement)
        }

        else -> null
    }
}

context(Converter)
private fun convertScalarType(
    type: KType,
    scalarValue: Scalar?,
    text: String,
    valueBase: ValueBase<Any?>?
): Any? {
    scalarValue?.sourceElement?.let {
        valueBase?.doApplyPsiTrace(it)
    }
    when {
        type.isSubtypeOf(SchemaEnum::class.starProjectedType) -> {
            return convertEnum(type, scalarValue)
        }

        type.isString -> return text
        type.isBoolean -> return text == "true"
        type.isInt -> return text.toInt()
        type.isPath -> return text.asAbsolutePath()
    }

    return null
}

context(Converter)
private fun convertEnum(
    type: KType,
    scalarValue: Scalar?,
    reportMismatch: Boolean = true
): SchemaEnum? {
    @Suppress("UNCHECKED_CAST")
    val allValues = type.unwrapKClass.declaredFunctions.firstOrNull {
        it.name == "values"
    }?.call() as? Array<SchemaEnum>

    val matchingEnumValue = allValues?.firstOrNull { it.schemaValue == scalarValue?.textValue }
    if (reportMismatch && matchingEnumValue == null && scalarValue != null) {
        problemReporter.reportMessage(
            BuildProblemImpl("product.unknown.enum.value",
                PsiBuildProblemSource(scalarValue.sourceElement),
                SchemaBundle.message(
                    if (allValues.orEmpty().size > 10) "product.unknown.enum.value.short"
                    else "product.unknown.enum.value",
                    type.unwrapKClass.simpleName?.splitByCamelHumps(),
                    scalarValue.textValue,
                    allValues?.joinToString { it.schemaValue }),
                Level.Error)
        )
    }
    return matchingEnumValue
}

private fun String.splitByCamelHumps(): String {
    val parts = mutableListOf<String>()
    var prevIndex = 0
    for ((index, letter) in withIndex()) {
        if (index > 0 && letter.isUpperCase()) {
            parts.add(substring(prevIndex, index))
            prevIndex = index
        }
    }
    parts.add(substring(prevIndex)) // last part
    return parts.joinToString(" ") { it.lowercase() }
}

context(Converter)
private fun instantiateDependency(
    scalarValue: Scalar?,
    applicableKeys: List<KeyWithContext>,
    path: Pointer,
    table: Map<KeyWithContext, AmperElementWrapper>,
    contexts: Set<String>
): Any? {
    val textValue = scalarValue?.textValue
    if ((scalarValue?.sourceElement?.language is YAMLLanguage
                || textValue == path.segmentName) && textValue != null) {
        val sourceElement = table[KeyWithContext(path, contexts)]?.sourceElement
        return instantiateDependency(textValue).also { dep ->
            sourceElement?.let { e ->
                applyDependencyTrace(dep, e)
            }
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
                instantiateDependency(segmentName).also { dep ->
                    sourceElement?.let {
                        applyDependencyTrace(dep, it)
                    }
                    when (specialValue) {
                        "exported" -> {
                            dep.exported = true
                            dep::exported.valueBase?.doApplyPsiTrace(sourceElement)
                            return dep
                        }

                        "compile-only" -> {
                            dep.scope = DependencyScope.COMPILE_ONLY
                            dep::scope.valueBase?.doApplyPsiTrace(sourceElement)
                            return dep
                        }

                        "runtime-only" -> {
                            dep.scope = DependencyScope.RUNTIME_ONLY
                            dep::scope.valueBase?.doApplyPsiTrace(sourceElement)
                            return dep
                        }

                        "all" -> {
                            dep.scope = DependencyScope.ALL
                            dep::scope.valueBase?.doApplyPsiTrace(sourceElement)
                            return dep
                        }
                    }
                }
            }
        }
        else {
            if (path.segmentName?.toIntOrNull() != null) {
                val next = matchingKeys.map {
                    it.key.nextAfter(path)
                }.distinct()
                if (next.size == 1) {
                    val single = next.single()!!
                    return instantiateDependency(single.segmentName!!).also { dep ->
                        table[KeyWithContext(single, contexts)]?.sourceElement?.let {
                            applyDependencyTrace(dep, it)
                        }
                        readFromTable(dep, table, single, contexts)
                    }
                }
            }
            else {
                return instantiateDependency(path.segmentName!!).also { dep ->
                    table[KeyWithContext(path, contexts)]?.sourceElement?.let {
                        applyDependencyTrace(dep, it)
                    }
                    readFromTable(dep, table, path, contexts)
                }
            }
        }
    }
    return null
}

private fun applyDependencyTrace(dep: Dependency, e: PsiElement) {
    dep.doApplyPsiTrace(e)
    (dep as? ExternalMavenDependency)?.let {
        it::coordinates.valueBase?.doApplyPsiTrace(e)
    }
    (dep as? CatalogDependency)?.let {
        it::catalogKey.valueBase?.doApplyPsiTrace(e)
    }
    (dep as? InternalDependency)?.let {
        it::path.valueBase?.doApplyPsiTrace(e)
    }
}

context(Converter)
internal fun <T : Any> readFromTable(
    obj: T,
    table: Map<KeyWithContext, AmperElementWrapper>,
    path: Pointer = Pointer(),
    contexts: Set<String> = emptySet()
) {
    obj::class.schemaDeclaredMemberProperties()
        .filterIsInstance<KMutableProperty1<Any, Any?>>()
        .forEach { prop ->
            readTypedValue(prop.returnType, table, path + prop.name, contexts, prop.valueBase(obj))?.let {
                setPropertyValueSafe(prop, obj, it)
                table[KeyWithContext(path + prop.name, contexts)]?.let {
                    prop.valueBase(obj)?.doApplyPsiTrace(it.sourceElement)
                }
            }
    }
}

private fun KType.instantiateType(): Any {
    val kClass = unwrapKClass
    if (kClass.isSubclassOf(Set::class)) {
        return HashSet<Any>()
    }
    if (kClass.isSubclassOf(List::class)) {
        return ArrayList<Any>()
    }
    return kClass.findAnnotation<ImplicitConstructor>()?.constructedType?.createInstance()
        ?: kClass.createInstance()
}

open class AmperPsiAdapterVisitor {
    private val positionStack: Stack<String> = Stack()
    private val contextStack: Stack<Set<String>> = Stack()

    val position get() = positionStack.toList().let {
        var path = Pointer()
        for (item in it) {
            path += item
        }
        return@let path
    }
    val context get() = contextStack.toList().flatten().toSet()

    fun visitElement(element: PsiElement) {
        if (element.language is AmperLanguage) {
            object: AmperElementVisitor() {
                override fun visitContextBlock(o: AmperContextBlock) {
                    contextStack.push(o.contextNameList.contextNames)
                    super.visitContextBlock(o)
                    contextStack.pop()
                }

                override fun visitContextualStatement(o: AmperContextualStatement) {
                    contextStack.push(o.contextNameList.contextNames)
                    super.visitContextualStatement(o)
                    contextStack.pop()
                }

                override fun visitElement(o: PsiElement) {
                    ProgressIndicatorProvider.checkCanceled()
                    o.acceptChildren(this)
                }

                override fun visitProperty(o: AmperProperty) {
                    val parentObject = o.parent as? AmperObject
                    val props = parentObject?.propertyList.orEmpty()
                    if (props.isNotEmpty() && hasDuplicateProperties(props, o)) {
                        positionStack.push(props.indexOf(o).toString())
                    } else {
                        positionStack.push(
                            when (o.name) {
                                "testSettings" -> "test-settings"
                                "testDependencies" -> "test-dependencies"
                                null -> "[unnamed]"
                                else -> o.name
                            }
                        )
                    }
                    visitMappingEntry(MappingEntry(o))
                    super.visitProperty(o)
                    positionStack.pop()
                }

                private fun hasDuplicateProperties(
                    props: List<AmperProperty>,
                    o: AmperProperty
                ): Boolean {
                    val namesakeProps = props.filter { it.name == o.name }
                    val hadDuplicateProperties = namesakeProps.size > 1 && namesakeProps.map {
                        it.parentsOfType<AmperContextualElement>().map {
                            it.contexts
                        }.flatten().toSet() to it
                    }.groupBy({ it.first }) { it.second }.entries.any { it.value.size > 1 }
                    return hadDuplicateProperties
                }

                override fun visitLiteral(o: AmperLiteral) {
                    Scalar.from(o)?.let { visitScalar(it) }
                    super.visitLiteral(o)
                }
            }.visitElement(element)
        }
        else {
            object : YamlPsiElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressIndicatorProvider.checkCanceled()
                    element.acceptChildren(this)
                }

                override fun visitKeyValue(keyValue: YAMLKeyValue) {
                    val atSign = keyValue.keyText.indexOf('@')
                    if (atSign < 0) {
                        positionStack.push(keyValue.keyText)
                    }
                    else {
                        positionStack.push(keyValue.keyText.substring(0, atSign))
                        contextStack.push(keyValue.keyText.substring(atSign + 1).split('+').toSet())
                    }
                    visitMappingEntry(MappingEntry(keyValue))
                    super.visitKeyValue(keyValue)
                    positionStack.pop()
                    if (atSign >= 0) { contextStack.pop() }
                }

                override fun visitSequence(sequence: YAMLSequence) {
                    sequence.items.forEachIndexed { index, item ->
                        positionStack.push(index.toString())
                        item.value?.accept(this)
                        item.value?.let { visitSequenceItem(it, index) }
                        positionStack.pop()
                    }
                    // do not call super here!
                }

                override fun visitScalar(scalar: YAMLScalar) {
                    Scalar.from(scalar)?.let { visitScalar(it) }
                    super.visitScalar(scalar)
                }
            }.visitElement(element)
        }
    }

    open fun visitSequenceItem(item: PsiElement, index: Int) {}
    open fun visitMappingEntry(node: MappingEntry) {}
    open fun visitScalar(node: Scalar) {}
}

private fun <T : Traceable> T.doApplyPsiTrace(element: PsiElement?): T {
    val adjustedElement =
        MappingEntry.from(element)?.sourceElement ?:
        element?.let { MappingEntry.byValue(it) }?.sourceElement ?:
        element
    return applyPsiTrace(adjustedElement)
}

private val AmperContextualElement.contexts get() =
    when (this) {
        is AmperContextBlock -> contextNameList.contextNames
        is AmperContextualStatement -> contextNameList.contextNames
        else -> emptyList()
    }

private val List<AmperContextName>.contextNames get() = mapNotNull { it.identifier?.text }.toSet()