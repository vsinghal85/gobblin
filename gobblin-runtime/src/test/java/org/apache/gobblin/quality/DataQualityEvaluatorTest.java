/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.quality;

import java.util.ArrayList;
import java.util.List;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.WorkUnitState;
import org.apache.gobblin.qualitychecker.DataQualityStatus;
import org.apache.gobblin.runtime.JobState;
import org.apache.gobblin.runtime.TaskState;
import org.apache.gobblin.source.workunit.WorkUnit;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for {@link DataQualityEvaluator}
 */
public class DataQualityEvaluatorTest {

    @Test
    public void testDataQualityEvaluation() {
        // Create a new job state for testing data quality
        JobState jobState = new JobState("DataQualityTestJob", "DataQualityTestJob-1");
        List<TaskState> taskStates = new ArrayList<>();

        // Create task states with different data quality results
        for (int i = 0; i < 3; i++) {
            WorkUnit workUnit = WorkUnit.createEmpty();
            WorkUnitState workUnitState = new WorkUnitState(workUnit);
            workUnitState.setProp(ConfigurationKeys.JOB_ID_KEY, "DataQualityTestJob-1");
            workUnitState.setProp(ConfigurationKeys.TASK_ID_KEY, "DataQualityTask-" + i);
            workUnitState.setProp(ConfigurationKeys.DATASET_URN_KEY, "TestDataset");

            TaskState taskState = new TaskState(workUnitState);
            taskState.setTaskId("DataQualityTask-" + i);
            taskState.setWorkingState(WorkUnitState.WorkingState.SUCCESSFUL);

            // Set different data quality results for each task
            switch (i) {
                case 0:
                    // First task passes data quality
                    taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
                    break;
                case 1:
                    // Second task fails data quality
                    taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.FAILED.name());
                    break;
                case 2:
                    // Third task has no data quality result
                    break;
            }

            taskStates.add(taskState);
        }

        // Test the DataQualityEvaluator using static methods
        DataQualityEvaluator.DataQualityEvaluationResult result = DataQualityEvaluator.evaluateDataQuality(taskStates, jobState);

        // Verify evaluation results
        Assert.assertEquals(result.getQualityStatus(), DataQualityStatus.FAILED,
            "Overall quality should be FAILED when any task fails data quality");
        Assert.assertEquals(result.getTotalFiles(), 3, "Should have 3 total files");
        Assert.assertEquals(result.getPassedFiles(), 1, "Should have 1 passed file");
        Assert.assertEquals(result.getFailedFiles(), 1, "Should have 1 failed file");

        // Verify individual task states
        for (TaskState taskState : taskStates) {
            if (taskState.getTaskId().equals("DataQualityTask-0")) {
                Assert.assertEquals(taskState.getProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY), "PASSED",
                    "First task should have PASSED status");
            } else if (taskState.getTaskId().equals("DataQualityTask-1")) {
                Assert.assertEquals(taskState.getProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY), "FAILED",
                    "Second task should have FAILED status");
            } else if (taskState.getTaskId().equals("DataQualityTask-2")) {
                Assert.assertNull(taskState.getProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY),
                    "Third task should have no data quality result");
            }
        }
    }

    @Test
    public void testDatasetQualityEvaluation() {
        // Create a new job state for testing dataset-level data quality
        JobState jobState = new JobState("DatasetQualityTestJob", "DatasetQualityTestJob-1");
        JobState.DatasetState datasetState = new JobState.DatasetState("DatasetQualityTestJob", "DatasetQualityTestJob-1");

        // Create task states with different data quality results
        for (int i = 0; i < 3; i++) {
            WorkUnit workUnit = WorkUnit.createEmpty();
            WorkUnitState workUnitState = new WorkUnitState(workUnit);
            workUnitState.setProp(ConfigurationKeys.JOB_ID_KEY, "DatasetQualityTestJob-1");
            workUnitState.setProp(ConfigurationKeys.TASK_ID_KEY, "task-" + i);

            TaskState taskState = new TaskState(workUnitState);

            // Set different data quality statuses for different tasks using the correct key
            if (i == 0) {
                taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
            } else if (i == 1) {
                taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.FAILED.name());
            } else {
                taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
            }

            datasetState.addTaskState(taskState);
        }

        // Test the DataQualityEvaluator dataset-level evaluation
        DataQualityEvaluator.DataQualityEvaluationResult result =
            DataQualityEvaluator.evaluateAndReportDatasetQuality(datasetState, jobState);

        // Verify evaluation results
        Assert.assertEquals(result.getQualityStatus(), DataQualityStatus.FAILED,
            "Overall quality should be FAILED when any task fails data quality");
        Assert.assertEquals(result.getTotalFiles(), 3, "Should have 3 total files");
        Assert.assertEquals(result.getPassedFiles(), 2, "Should have 2 passed files");
        Assert.assertEquals(result.getFailedFiles(), 1, "Should have 1 failed file");

        // Verify dataset quality status is stored correctly in jobState (where DataQualityEvaluator sets it)
        Assert.assertEquals(jobState.getProp(ConfigurationKeys.DATASET_QUALITY_STATUS_KEY), DataQualityStatus.FAILED.name(),
            "JobState should be marked as FAILED when any task fails data quality");

        // Verify task states are preserved
        Assert.assertEquals(datasetState.getTaskStates().size(), 3, "All task states should be preserved");
    }

    @Test
    public void testAllPassedScenario() {
        // Create a job state for testing all passed scenario
        JobState jobState = new JobState("AllPassedTestJob", "AllPassedTestJob-1");
        JobState.DatasetState datasetState = new JobState.DatasetState("AllPassedTestJob", "AllPassedTestJob-1");

        // Create task states with all PASSED data quality results
        for (int i = 0; i < 2; i++) {
            WorkUnit workUnit = WorkUnit.createEmpty();
            WorkUnitState workUnitState = new WorkUnitState(workUnit);
            workUnitState.setProp(ConfigurationKeys.JOB_ID_KEY, "AllPassedTestJob-1");
            workUnitState.setProp(ConfigurationKeys.TASK_ID_KEY, "task-" + i);

            TaskState taskState = new TaskState(workUnitState);
            taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
            datasetState.addTaskState(taskState);
        }

        // Test the DataQualityEvaluator
        DataQualityEvaluator.DataQualityEvaluationResult result =
            DataQualityEvaluator.evaluateAndReportDatasetQuality(datasetState, jobState);
        System.out.println("Data Quality Evaluation Result: " + result);
        // Verify evaluation results
        Assert.assertEquals(result.getQualityStatus(), DataQualityStatus.PASSED,
            "Overall quality should be PASSED when all tasks pass data quality");
        Assert.assertEquals(result.getTotalFiles(), 2, "Should have 2 total files");
        Assert.assertEquals(result.getPassedFiles(), 2, "Should have 2 passed files");
        Assert.assertEquals(result.getFailedFiles(), 0, "Should have 0 failed files");

        // Verify dataset quality status is stored correctly in jobState (where DataQualityEvaluator sets it)
        Assert.assertEquals(jobState.getProp(ConfigurationKeys.DATASET_QUALITY_STATUS_KEY), DataQualityStatus.PASSED.name(),
            "JobState should be marked as PASSED when all tasks pass data quality");
    }

    @Test
    public void testEmptyTaskStates() {
        // Create a job state for testing empty task states scenario
        JobState jobState = new JobState("EmptyTestJob", "EmptyTestJob-1");
        JobState.DatasetState datasetState = new JobState.DatasetState("EmptyTestJob", "EmptyTestJob-1");

        // Test the DataQualityEvaluator with no task states
        DataQualityEvaluator.DataQualityEvaluationResult result =
            DataQualityEvaluator.evaluateAndReportDatasetQuality(datasetState, jobState);

        // Verify evaluation results for empty task states
        Assert.assertEquals(result.getQualityStatus(), DataQualityStatus.PASSED,
            "Overall quality should be PASSED when no task states exist");
        Assert.assertEquals(result.getTotalFiles(), 0, "Should have 0 total files");
        Assert.assertEquals(result.getPassedFiles(), 0, "Should have 0 passed files");
        Assert.assertEquals(result.getFailedFiles(), 0, "Should have 0 failed files");

        // Verify dataset quality status is stored correctly in jobState (where DataQualityEvaluator sets it)
        Assert.assertEquals(jobState.getProp(ConfigurationKeys.DATASET_QUALITY_STATUS_KEY), DataQualityStatus.PASSED.name(),
            "JobState should be marked as PASSED when no task states exist");
    }

    @Test
    public void testWithSomeEmptyTaskStates() {
        // Create a job state for testing mixed task states scenario
        JobState jobState = new JobState("MixedTestJob", "MixedTestJob-1");
        JobState.DatasetState datasetState = new JobState.DatasetState("MixedTestJob", "MixedTestJob-1");

        // Create task states with mixed data quality results
        for (int i = 0; i < 4; i++) {
            WorkUnit workUnit = WorkUnit.createEmpty();
            WorkUnitState workUnitState = new WorkUnitState(workUnit);
            workUnitState.setProp(ConfigurationKeys.JOB_ID_KEY, "MixedTestJob-1");
            workUnitState.setProp(ConfigurationKeys.TASK_ID_KEY, "task-" + i);

            TaskState taskState = new TaskState(workUnitState);

            // Set data quality status for some tasks, leave others empty
            if (i == 0) {
                taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
            } else if (i == 1) {
                taskState.setProp(ConfigurationKeys.TASK_LEVEL_POLICY_RESULT_KEY, DataQualityStatus.PASSED.name());
            } else {
                // Tasks 2 and 3 have no data quality status set (null)
                // This simulates tasks that were not evaluated for data quality
            }

            datasetState.addTaskState(taskState);
        }

        // Test the DataQualityEvaluator with mixed task states
        DataQualityEvaluator.DataQualityEvaluationResult result =
            DataQualityEvaluator.evaluateAndReportDatasetQuality(datasetState, jobState);

        // Verify evaluation results for mixed task states
        Assert.assertEquals(result.getQualityStatus(), DataQualityStatus.PASSED,
            "Overall quality should be PASSED when some tasks pass and others are not evaluated");
        Assert.assertEquals(result.getTotalFiles(), 4, "Should have 4 total files");
        Assert.assertEquals(result.getPassedFiles(), 2, "Should have 2 passed files");
        Assert.assertEquals(result.getFailedFiles(), 0, "Should have 0 failed files");
        Assert.assertEquals(result.getNonEvaluatedFiles(), 2, "Should have 2 non-evaluated files");

        // Verify dataset quality status is stored correctly in jobState (where DataQualityEvaluator sets it)
        Assert.assertEquals(jobState.getProp(ConfigurationKeys.DATASET_QUALITY_STATUS_KEY), DataQualityStatus.PASSED.name(),
            "JobState should be marked as PASSED when some tasks pass and others are not evaluated");

        // Verify task states are preserved
        Assert.assertEquals(datasetState.getTaskStates().size(), 4, "All task states should be preserved");
    }
}