/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.use

private const val AMPER_SCOPE_NAME = "amper"

val tracer: Tracer
    get() = GlobalOpenTelemetry.getTracer(AMPER_SCOPE_NAME)

val meter: Meter
    get() = GlobalOpenTelemetry.getMeter(AMPER_SCOPE_NAME)

/**
 * Don't use this method; use [use] instead.
 */
@PublishedApi // to be able to mark it at least 'internal' if not private, and use from a public function
internal inline fun <T> Span.useWithoutActiveScope(operation: (Span) -> T): T {
    try {
        return operation(this)
    }
    catch (e: CancellationException) {
        setAttribute("cancelled", true)
        setStatus(StatusCode.ERROR)
        throw e
    }
    catch (e: Throwable) {
        recordException(e)
        setStatus(StatusCode.ERROR)
        throw e
    }
    finally {
        end()
    }
}

inline fun <T> Span.use(operation: (Span) -> T): T {
    return useWithoutActiveScope { span ->
        span.makeCurrent().use {
            operation(span)
        }
    }
}

inline fun <T> SpanBuilder.use(operation: (Span) -> T): T {
    return startSpan().use(operation)
}

suspend inline fun <T> SpanBuilder.useWithScope(crossinline operation: suspend CoroutineScope.(Span) -> T): T {
    val span = startSpan()
    return withContext(Context.current().with(span).asContextElement()) {
        span.useWithoutActiveScope {
            operation(span)
        }
    }
}

fun spanBuilder(spanName: String): SpanBuilder = tracer.spanBuilder(spanName)
