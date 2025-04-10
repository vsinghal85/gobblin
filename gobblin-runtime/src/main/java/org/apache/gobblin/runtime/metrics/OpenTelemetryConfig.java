package org.apache.gobblin.runtime.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;

public class OpenTelemetryConfig {
    private static final String SERVICE_NAME = "gobblin";
    private static final String SERVICE_VERSION = "1.0.0";

    public static void initialize() {
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(
                ResourceAttributes.SERVICE_NAME, SERVICE_NAME,
                ResourceAttributes.SERVICE_VERSION, SERVICE_VERSION)));

        SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .build();

        OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(sdkMeterProvider)
            .build();

        // Set the global OpenTelemetry instance
        io.opentelemetry.api.GlobalOpenTelemetry.set(openTelemetrySdk);
    }
} 