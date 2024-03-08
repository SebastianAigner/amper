/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.core.AmperBuild
import org.jetbrains.amper.diagnostics.DeadLockMonitor
import org.jetbrains.amper.diagnostics.DynamicFileWriter
import org.jetbrains.amper.diagnostics.DynamicLevelConsoleWriter
import org.jetbrains.amper.diagnostics.JaegerJsonSpanExporter
import org.slf4j.LoggerFactory
import org.tinylog.Level
import org.tinylog.core.TinylogLoggingProvider
import org.tinylog.provider.ProviderRegistry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories

object CliEnvironmentInitializer {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val init by lazy {
        // see https://github.com/Anamorphosee/stacktrace-decoroutinator#motivation
        // Temporary disabled due to unresolved issues with it AMPER-396 CLI: Provide coroutine stacktraces
        // DecoroutinatorRuntime.load()

        // coroutines debug probes, required to dump coroutines
        DebugProbes.enableCreationStackTraces = false
        DebugProbes.install()
    }

    fun setup() = init

    fun setupDeadLockMonitor(logsRoot: AmperBuildLogsRoot) {
        DeadLockMonitor.install(logsRoot)
    }

    fun setupLogging(logsRoot: AmperBuildLogsRoot, enableConsoleDebugLogging: Boolean) {
        logsRoot.path.createDirectories()

        val loggingProvider = ProviderRegistry.getLoggingProvider() as TinylogLoggingProvider

        loggingProvider.writers.filterIsInstance<DynamicLevelConsoleWriter>().single()
            .setLevel(if (enableConsoleDebugLogging) Level.DEBUG else Level.INFO)

        loggingProvider.writers.filterIsInstance<DynamicFileWriter>().single { it.level == Level.DEBUG }
            .setFile(logsRoot.path.resolve("${logFilePrefix}-debug.log"))

        loggingProvider.writers.filterIsInstance<DynamicFileWriter>().single { it.level == Level.INFO }
            .setFile(logsRoot.path.resolve("${logFilePrefix}-info.log"))
    }

    fun setupTelemetry(logsRoot: AmperBuildLogsRoot) {
        // TODO: Implement some kind of background batch processing like in intellij

        val spansFile = logsRoot.path.resolve("${logFilePrefix}-jaeger.json")

        val jaegerJsonSpanExporter = JaegerJsonSpanExporter(
            file = spansFile,
            serviceName = resource.getAttribute(AttributeKey.stringKey("service.name"))!!,
            serviceNamespace = resource.getAttribute(AttributeKey.stringKey("service.namespace"))!!,
            serviceVersion = resource.getAttribute(AttributeKey.stringKey("service.version"))!!,
        )

        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(object : SpanExporter {
                override fun export(spans: Collection<SpanData>): CompletableResultCode {
                    runBlocking {
                        jaegerJsonSpanExporter.export(spans)
                    }
                    return CompletableResultCode.ofSuccess()
                }

                override fun flush(): CompletableResultCode {
                    runBlocking {
                        jaegerJsonSpanExporter.flush()
                    }
                    return CompletableResultCode.ofSuccess()
                }

                override fun shutdown(): CompletableResultCode {
                    runBlocking {
                        jaegerJsonSpanExporter.shutdown()
                    }
                    return CompletableResultCode.ofSuccess()
                }
            }))
            .setResource(resource)
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        GlobalOpenTelemetry.set(openTelemetry)

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            try {
                openTelemetry.close()
            } catch (t: Throwable) {
                LoggerFactory.getLogger(javaClass).error("Exception on shutdown: ${t.message}", t)
            }
        })
    }

    val logFilePrefix by lazy {
        "amper-${currentTimestamp()}-${AmperBuild.BuildNumber}"
    }

    fun currentTimestamp() = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())

    private val resource: Resource = Resource.create(
        Attributes.builder()
            .put(AttributeKey.stringKey("service.name"), "Amper")
            .put(AttributeKey.stringKey("service.version"), AmperBuild.BuildNumber)
            .put(AttributeKey.stringKey("service.namespace"), "amper")
            .put(AttributeKey.stringKey("os.type"), System.getProperty("os.name"))
            .put(AttributeKey.stringKey("os.version"), System.getProperty("os.version").lowercase())
            .put(AttributeKey.stringKey("host.arch"), System.getProperty("os.arch"))
            .put(AttributeKey.stringKey("service.instance.id"), DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            .build()
    )
}