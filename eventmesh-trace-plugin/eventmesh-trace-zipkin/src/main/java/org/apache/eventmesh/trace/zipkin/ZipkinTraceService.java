/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.trace.zipkin;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import org.apache.eventmesh.common.config.Config;
import org.apache.eventmesh.trace.api.EventMeshTraceService;
import org.apache.eventmesh.trace.api.config.ExporterConfiguration;
import org.apache.eventmesh.trace.api.exception.TraceException;
import org.apache.eventmesh.trace.zipkin.common.ZipkinConstants;
import org.apache.eventmesh.trace.zipkin.config.ZipkinConfiguration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import lombok.Data;


/**
 * ZipkinTraceService
 */
@Config(field = "zipkinConfiguration")
@Config(field = "exporterConfiguration")
@Data
public class ZipkinTraceService implements EventMeshTraceService {

    private transient SdkTracerProvider sdkTracerProvider;
    private transient SpanProcessor spanProcessor;

    private transient Thread shutdownHook;


    private transient Tracer tracer;
    private transient TextMapPropagator textMapPropagator;

    /**
     * Unified configuration class corresponding to zipkin.properties
     */
    private transient ZipkinConfiguration zipkinConfiguration;

    /**
     * Unified configuration class corresponding to exporter.properties
     */
    private transient ExporterConfiguration exporterConfiguration;

    private transient ZipkinSpanExporter zipkinExporter;

    @Override
    public void init() {
        //zipkin's config
        final String eventMeshZipkinIP = zipkinConfiguration.getEventMeshZipkinIP();
        final int eventMeshZipkinPort = zipkinConfiguration.getEventMeshZipkinPort();

        //exporter's config
        final int eventMeshTraceExportInterval = exporterConfiguration.getEventMeshTraceExportInterval();
        final int eventMeshTraceExportTimeout = exporterConfiguration.getEventMeshTraceExportTimeout();
        final int eventMeshTraceMaxExportSize = exporterConfiguration.getEventMeshTraceMaxExportSize();
        final int eventMeshTraceMaxQueueSize = exporterConfiguration.getEventMeshTraceMaxQueueSize();

        final String httpUrl = String.format("http://%s:%s", eventMeshZipkinIP, eventMeshZipkinPort);
        zipkinExporter =
            ZipkinSpanExporter.builder().setEndpoint(httpUrl + ZipkinConstants.ENDPOINT_V2_SPANS).build();
        spanProcessor = BatchSpanProcessor.builder(zipkinExporter)
            .setScheduleDelay(eventMeshTraceExportInterval, TimeUnit.SECONDS)
            .setExporterTimeout(eventMeshTraceExportTimeout, TimeUnit.SECONDS)
            .setMaxExportBatchSize(eventMeshTraceMaxExportSize)
            .setMaxQueueSize(eventMeshTraceMaxQueueSize)
            .build();

        //set the trace service's name
        final Resource serviceNameResource =
            Resource.create(Attributes.of(stringKey("service.name"), ZipkinConstants.SERVICE_NAME));

        sdkTracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(spanProcessor)
            .setResource(Resource.getDefault().merge(serviceNameResource))
            .build();

        final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .setTracerProvider(sdkTracerProvider)
            .build();

        //TODO serviceName???
        tracer = openTelemetry.getTracer(ZipkinConstants.SERVICE_NAME);
        textMapPropagator = openTelemetry.getPropagators().getTextMapPropagator();
        shutdownHook = new Thread(sdkTracerProvider::close);
        shutdownHook.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public Context extractFrom(Context context, Map<String, Object> map) throws TraceException {
        textMapPropagator.extract(context, map, new TextMapGetter<Map<String, Object>>() {
            @Override
            public Iterable<String> keys(@Nonnull Map<String, Object> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(@Nonnull Map<String, Object> carrier, String key) {
                return Optional.ofNullable(carrier.get(key)).map(Object::toString).orElse(null);
            }
        });
        return context;
    }

    @Override
    public void inject(Context context, Map<String, Object> map) {
        textMapPropagator.inject(context, map, (carrier, key, value) -> carrier.put(key, value));
    }

    @Override
    public Span createSpan(String spanName, SpanKind spanKind, long startTime, TimeUnit timeUnit,
        Context context, boolean isSpanFinishInOtherThread)
        throws TraceException {
        return tracer.spanBuilder(spanName)
            .setParent(context)
            .setSpanKind(spanKind)
            .setStartTimestamp(startTime, timeUnit)
            .startSpan();
    }

    @Override
    public Span createSpan(String spanName, SpanKind spanKind, Context context,
        boolean isSpanFinishInOtherThread) throws TraceException {
        return tracer.spanBuilder(spanName)
            .setParent(context)
            .setSpanKind(spanKind)
            .setStartTimestamp(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .startSpan();
    }

    @Override
    public void shutdown() throws TraceException {
        //todo: check the spanProcessor if it was already close

        Exception ex = null;

        try {
            if (sdkTracerProvider != null) {
                sdkTracerProvider.close();
            }
        } catch (Exception e) {
            ex = e;
        }

        try {
            if (spanProcessor != null) {
                spanProcessor.close();
            }
        } catch (Exception e) {
            ex = e;
        }

        try {
            if (zipkinExporter != null) {
                zipkinExporter.close();
            }
        } catch (Exception e) {
            ex = e;
        }

        if (ex != null) {
            throw new TraceException("trace close error", ex);
        }

        //todo: turn the value of useTrace in AbstractHTTPServer into false
    }

    public ZipkinConfiguration getClientConfiguration() {
        return this.zipkinConfiguration;
    }

    public ExporterConfiguration getExporterConfiguration() {
        return this.exporterConfiguration;
    }
}
