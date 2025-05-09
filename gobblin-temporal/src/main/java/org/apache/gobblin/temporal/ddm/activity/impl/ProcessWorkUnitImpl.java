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

package org.apache.gobblin.temporal.ddm.activity.impl;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.google.common.collect.Lists;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.broker.gobblin_scopes.GobblinScopeTypes;
import org.apache.gobblin.broker.iface.SharedResourcesBroker;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.configuration.WorkUnitState;
import org.apache.gobblin.data.management.copy.CopyEntity;
import org.apache.gobblin.data.management.copy.CopySource;
import org.apache.gobblin.data.management.copy.CopyableFile;
import org.apache.gobblin.metastore.StateStore;
import org.apache.gobblin.metrics.event.EventSubmitter;
import org.apache.gobblin.runtime.AbstractTaskStateTracker;
import org.apache.gobblin.runtime.GobblinMultiTaskAttempt;
import org.apache.gobblin.runtime.JobState;
import org.apache.gobblin.runtime.Task;
import org.apache.gobblin.runtime.TaskExecutor;
import org.apache.gobblin.runtime.TaskState;
import org.apache.gobblin.runtime.TaskStateTracker;
import org.apache.gobblin.runtime.troubleshooter.AutomaticTroubleshooter;
import org.apache.gobblin.runtime.troubleshooter.AutomaticTroubleshooterFactory;
import org.apache.gobblin.runtime.troubleshooter.IssueRepository;
import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.temporal.ddm.activity.ProcessWorkUnit;
import org.apache.gobblin.temporal.ddm.util.JobStateUtils;
import org.apache.gobblin.temporal.ddm.work.WorkUnitClaimCheck;
import org.apache.gobblin.temporal.ddm.work.assistance.Help;
import org.apache.gobblin.util.ExecutorsUtils;
import org.apache.gobblin.util.JobLauncherUtils;


@Slf4j
public class ProcessWorkUnitImpl implements ProcessWorkUnit {
  private static final int LOG_EXTENDED_PROPS_EVERY_WORK_UNITS_STRIDE = 100;

  private static final String MAX_SOURCE_PATHS_TO_LOG_PER_MULTI_WORK_UNIT = ProcessWorkUnitImpl.class.getName() + ".maxSourcePathsToLogPerMultiWorkUnit";
  private static final int DEFAULT_MAX_SOURCE_PATHS_TO_LOG_PER_MULTI_WORK_UNIT = 5;

  @Override
  public int processWorkUnit(WorkUnitClaimCheck wu) {
    ActivityExecutionContext activityExecutionContext = Activity.getExecutionContext();
    ScheduledExecutorService heartBeatExecutor = Executors.newSingleThreadScheduledExecutor(
        ExecutorsUtils.newThreadFactory(com.google.common.base.Optional.of(log),
            com.google.common.base.Optional.of("CommitActivityHeartBeatExecutor")));
    AutomaticTroubleshooter troubleshooter = null;
    EventSubmitter eventSubmitter = wu.getEventSubmitterContext().create();
    String correlator = String.format("(M)WU [%s]", wu.getCorrelator());
    try (FileSystem fs = Help.loadFileSystemForce(wu)) {
      List<WorkUnit> workUnits = loadFlattenedWorkUnits(wu, fs);
      log.info("{} - loaded; found {} workUnits", correlator, workUnits.size());
      JobState jobState = Help.loadJobState(wu, fs);
      int heartBeatInterval = JobStateUtils.getHeartBeatInterval(jobState);
      heartBeatExecutor.scheduleAtFixedRate(() -> activityExecutionContext.heartbeat("Running ProcessWorkUnit Activity"),
          heartBeatInterval, heartBeatInterval, TimeUnit.MINUTES);
      troubleshooter = AutomaticTroubleshooterFactory.createForJob(jobState.getProperties());
      troubleshooter.start();
      return execute(workUnits, wu, jobState, fs, troubleshooter.getIssueRepository(), jobState.getProperties());
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      Help.finalizeTroubleshooting(troubleshooter, eventSubmitter, log, correlator);
      ExecutorsUtils.shutdownExecutorService(heartBeatExecutor, com.google.common.base.Optional.of(log));
    }
  }

  protected List<WorkUnit> loadFlattenedWorkUnits(WorkUnitClaimCheck wu, FileSystem fs) throws IOException {
    Path wuPath = new Path(wu.getWorkUnitPath());
    WorkUnit workUnit = JobLauncherUtils.createEmptyWorkUnitPerExtension(wuPath);
    Help.deserializeStateWithRetries(fs, wuPath, workUnit, wu);
    return JobLauncherUtils.flattenWorkUnits(Lists.newArrayList(workUnit));
  }

  /**
   * NOTE: adapted from {@link org.apache.gobblin.runtime.mapreduce.MRJobLauncher.TaskRunner#run(org.apache.hadoop.mapreduce.Mapper.Context)}
   * @return count of how many tasks executed (0 if execution ultimately failed, but we *believe* TaskState should already have been recorded beforehand)
   */
  protected int execute(List<WorkUnit> workUnits, WorkUnitClaimCheck wu, JobState jobState, FileSystem fs, IssueRepository issueRepository,
                        Properties jobProperties) throws IOException, InterruptedException {
    String containerId = "container-id-for-wu-" + wu.getCorrelator();
    StateStore<TaskState> taskStateStore = Help.openTaskStateStore(wu, fs);

    TaskStateTracker taskStateTracker = createEssentializedTaskStateTracker(wu);
    TaskExecutor taskExecutor = new TaskExecutor(jobProperties);
    GobblinMultiTaskAttempt.CommitPolicy multiTaskAttemptCommitPolicy = GobblinMultiTaskAttempt.CommitPolicy.IMMEDIATE; // as no speculative exec

    SharedResourcesBroker<GobblinScopeTypes> resourcesBroker = JobStateUtils.getSharedResourcesBroker(jobState);
    Optional<String> optWorkUnitsDesc = getOptWorkUnitsDesc(workUnits, wu.getWorkUnitPath(), jobState);
    log.info("WU [{}] - submitting {} workUnits {}",
        wu.getCorrelator(),
        workUnits.size(),
        optWorkUnitsDesc.orElse(""));
    log.debug("WU [{}] - (first) workUnit: {}", wu.getCorrelator(), workUnits.isEmpty() ? "<<absent>>" : workUnits.get(0).toJsonString());

    GobblinMultiTaskAttempt taskAttempt = GobblinMultiTaskAttempt.runWorkUnits(
        jobState.getJobId(), containerId, jobState, workUnits,
        taskStateTracker, taskExecutor, taskStateStore, multiTaskAttemptCommitPolicy,
        resourcesBroker, issueRepository, createInterruptionPredicate(fs, jobState));
    return taskAttempt.getNumTasksCreated();
  }

  protected TaskStateTracker createEssentializedTaskStateTracker(WorkUnitClaimCheck wu) {
    return new AbstractTaskStateTracker(new Properties(), log) {
      @Override
      public void registerNewTask(Task task) {
        // TODO: shall we schedule metrics update based on config?
      }

      @Override
      public void onTaskRunCompletion(Task task) {
        task.markTaskCompletion();
      }

      @Override
      public void onTaskCommitCompletion(Task task) {
        TaskState taskState = task.getTaskState();
        // TODO: if metrics configured, report them now
        log.info("WU [{} = {}] - finished commit after {}ms with state {}{}", wu.getCorrelator(), task.getTaskId(),
            taskState.getTaskDuration(), taskState.getWorkingState(),
            taskState.getWorkingState().equals(WorkUnitState.WorkingState.SUCCESSFUL) && taskState.contains(ConfigurationKeys.WRITER_OUTPUT_DIR)
                ? (" to: " + taskState.getProp(ConfigurationKeys.WRITER_OUTPUT_DIR)) : "");
        log.debug("WU [{} = {}] - task state: {}", wu.getCorrelator(), task.getTaskId(),
            taskState.toJsonString(shouldUseExtendedLogging(wu)));
        getOptCopyableFile(taskState).ifPresent(copyableFile -> {
          log.info("WU [{} = {}] - completed copyableFile: {}", wu.getCorrelator(), task.getTaskId(),
              copyableFile.toJsonString(shouldUseExtendedLogging(wu)));
        });
      }
    };
  }

  protected static Optional<String> getOptWorkUnitsDesc(List<WorkUnit> workUnits, String workUnitsPath, JobState jobState) {
    List<String> fileSourcePaths = workUnits.stream()
        .map(workUnit -> getOptCopyableFileSourcePathDesc(workUnit, workUnitsPath))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toList());
    if (fileSourcePaths.isEmpty()) {
      // TODO - describe WorkUnits other than `CopyableFile`
      return Optional.empty();
    } else {
      return Optional.of(getSourcePathsToLog(fileSourcePaths, jobState)).map(pathsToLog ->
          "for copying source files: "
              + (pathsToLog.size() == workUnits.size() ? "" : ("**first " + pathsToLog.size() + " only** "))
              + pathsToLog
      );
    }
  }

  protected static Optional<String> getOptCopyableFileSourcePathDesc(WorkUnit workUnit, String workUnitPath) {
    return getOptWorkUnitCopyEntityClass(workUnit, workUnitPath).flatMap(copyEntityClass ->
        getOptCopyableFile(copyEntityClass, workUnit).map(copyableFile ->
            copyableFile.getOrigin().getPath().toString()));
  }

  protected static Optional<CopyableFile> getOptCopyableFile(TaskState taskState) {
    return getOptTaskStateCopyEntityClass(taskState).flatMap(copyEntityClass ->
        getOptCopyableFile(copyEntityClass, taskState));
  }

  protected static Optional<CopyableFile> getOptCopyableFile(Class<?> copyEntityClass, State state) {
    log.debug("(state) {} got (copyEntity) {}", state.getClass().getName(), copyEntityClass.getName());
    if (CopyableFile.class.isAssignableFrom(copyEntityClass)) {
      String serialization = state.getProp(CopySource.SERIALIZED_COPYABLE_FILE);
      if (serialization != null) {
        return Optional.of((CopyableFile) CopyEntity.deserialize(serialization));
      }
    }
    return Optional.empty();
  }

  protected static Optional<Class<?>> getOptWorkUnitCopyEntityClass(WorkUnit workUnit, String workUnitPath) {
    return getOptCopyEntityClass(workUnit, "workUnit '" + workUnitPath + "'");
  }

  protected static Optional<Class<?>> getOptTaskStateCopyEntityClass(TaskState taskState) {
    return getOptCopyEntityClass(taskState, "taskState '" + taskState.getTaskId() + "'");
  }

  protected static Optional<Class<?>> getOptCopyEntityClass(State state, String logDesc) {
    try {
      return Optional.of(CopySource.getCopyEntityClass(state));
    } catch (IOException ioe) {
      return Optional.empty();
    }
  }

  protected Predicate<GobblinMultiTaskAttempt> createInterruptionPredicate(FileSystem fs, JobState jobState) {
    // TODO - decide whether to support... and if so, employ a useful path; otherwise, just evaluate predicate to always false
    Path interruptionPath = new Path("/not/a/real/path/that/should/ever/exist!");
    return createInterruptionPredicate(fs, interruptionPath);
  }

  protected Predicate<GobblinMultiTaskAttempt> createInterruptionPredicate(FileSystem fs, Path interruptionPath) {
    return  (gmta) -> {
      try {
        return fs.exists(interruptionPath);
      } catch (IOException ioe) {
        return false;
      }
    };
  }

  protected boolean shouldUseExtendedLogging(WorkUnitClaimCheck wu) {
    try {
      return Long.parseLong(wu.getCorrelator()) % LOG_EXTENDED_PROPS_EVERY_WORK_UNITS_STRIDE == 0;
    } catch (NumberFormatException nfe) {
      log.warn("unexpected, non-numeric correlator: '{}'", wu.getCorrelator());
      return false;
    }
  }

  private static List<String> getSourcePathsToLog(List<String> sourcePaths, JobState jobState) {
    int maxPathsToLog = getMaxSourcePathsToLogPerMultiWorkUnit(jobState);
    int numPathsToLog = Math.min(sourcePaths.size(), maxPathsToLog);
    return sourcePaths.subList(0, numPathsToLog);
  }

  private static int getMaxSourcePathsToLogPerMultiWorkUnit(State jobState) {
    return jobState.getPropAsInt(MAX_SOURCE_PATHS_TO_LOG_PER_MULTI_WORK_UNIT, DEFAULT_MAX_SOURCE_PATHS_TO_LOG_PER_MULTI_WORK_UNIT);
  }
}
