package org.apache.gobblin.runtime.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

import org.apache.gobblin.configuration.State;
import org.apache.gobblin.qualitychecker.task.TaskLevelPolicy;
import org.apache.gobblin.qualitychecker.task.TaskLevelPolicyCheckResults;

public class DataQualityMetrics {
    private static final String METER_NAME = "gobblin.data.quality";
    private static final String QUALITY_CHECK_FAILURE_COUNTER = "data.quality.check.failure";
    private static final String TASK_ID_KEY = "task.id";
    private static final String JOB_ID_KEY = "job.id";
    private static final String POLICY_TYPE_KEY = "policy.type";
    private static final String POLICY_CLASS_KEY = "policy.class";

    private final LongCounter qualityCheckFailureCounter;
    private final String taskId;
    private final String jobId;

    public DataQualityMetrics(OpenTelemetry openTelemetry, State state) {
        Meter meter = openTelemetry.getMeter(METER_NAME);
        this.qualityCheckFailureCounter = meter.counterBuilder(QUALITY_CHECK_FAILURE_COUNTER)
            .setDescription("Number of data quality check failures")
            .setUnit("1")
            .build();

        this.taskId = state.getProp("task.id", "unknown");
        this.jobId = state.getProp("job.id", "unknown");
    }

    public void recordTaskLevelPolicyFailure(TaskLevelPolicy policy) {
        AttributesBuilder attributes = Attributes.builder()
            .put(TASK_ID_KEY, this.taskId)
            .put(JOB_ID_KEY, this.jobId)
            .put(POLICY_TYPE_KEY, policy.getType().name())
            .put(POLICY_CLASS_KEY, policy.getClass().getName());

        this.qualityCheckFailureCounter.add(1, attributes.build());
    }

    public void recordRowLevelPolicyFailure(String policyClass) {
        AttributesBuilder attributes = Attributes.builder()
            .put(TASK_ID_KEY, this.taskId)
            .put(JOB_ID_KEY, this.jobId)
            .put(POLICY_CLASS_KEY, policyClass);

        this.qualityCheckFailureCounter.add(1, attributes.build());
    }
} 