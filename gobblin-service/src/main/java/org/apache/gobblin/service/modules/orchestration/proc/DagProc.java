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

package org.apache.gobblin.service.modules.orchestration.proc;

import java.io.IOException;

import com.typesafe.config.Config;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.event.EventSubmitter;
import org.apache.gobblin.service.modules.flowgraph.Dag;
import org.apache.gobblin.service.modules.flowgraph.DagNodeId;
import org.apache.gobblin.service.modules.orchestration.DagActionStore;
import org.apache.gobblin.service.modules.orchestration.DagManagementStateStore;
import org.apache.gobblin.service.modules.orchestration.DagUtils;
import org.apache.gobblin.service.modules.orchestration.task.DagProcessingEngineMetrics;
import org.apache.gobblin.service.modules.orchestration.task.DagTask;


/**
 * Responsible for performing the actual work for a given {@link DagTask} by first initializing its state, performing
 * actions based on the type of {@link DagTask}. Submitting events in time is found to be important in
 * <a href="https://github.com/apache/gobblin/pull/3641">PR#3641</a>, hence initialize and act methods submit events as
 * they happen.
 * Parameter T is the type of object that needs an initialisation before the dag proc does the main work in `act` method.
 * This object is then passed to `act` method.
 *
 * @param <T> type of the initialization "state" on which to {@link DagProc#act}
 */
@Alpha
@Data
@Slf4j
public abstract class DagProc<T> {
  protected final DagTask dagTask;
  @Getter protected final Dag.DagId dagId;
  @Getter protected final DagNodeId dagNodeId;
  protected static final MetricContext metricContext = Instrumented.getMetricContext(new State(), DagProc.class);
  protected static final EventSubmitter eventSubmitter = new EventSubmitter.Builder(
      metricContext, "org.apache.gobblin.service").build();

  public DagProc(DagTask dagTask, Config config) {
    this.dagTask = dagTask;
    this.dagId = DagUtils.generateDagId(this.dagTask.getDagAction().getFlowGroup(),
        this.dagTask.getDagAction().getFlowName(), this.dagTask.getDagAction().getFlowExecutionId());
    this.dagNodeId = this.dagTask.getDagAction().getDagNodeId();
  }

  public final void process(DagManagementStateStore dagManagementStateStore,
      DagProcessingEngineMetrics dagProcEngineMetrics) throws IOException {
    T state;
    try {
      logContextualizedInfo("initializing");
      state = initialize(dagManagementStateStore);
      dagProcEngineMetrics.markDagActionsInitialize(getDagActionType(), true);
    } catch (Exception e) {
      dagProcEngineMetrics.markDagActionsInitialize(getDagActionType(), false);
      throw e;
    }
      logContextualizedInfo("ready to process");
      act(dagManagementStateStore, state, dagProcEngineMetrics);
      logContextualizedInfo("processed");
  }

  protected abstract T initialize(DagManagementStateStore dagManagementStateStore) throws IOException;

  protected abstract void act(DagManagementStateStore dagManagementStateStore, T state,
      DagProcessingEngineMetrics dagProcEngineMetrics) throws IOException;

  public DagActionStore.DagActionType getDagActionType() {
    return this.dagTask.getDagAction().getDagActionType();
  }

  /** @return `message` formatted with class name and {@link #getDagId()} */
  public String contextualizeStatus(String message) {
    return String.format("%s - %s - dagId : %s", getClass().getSimpleName(), message, this.dagId);
  }

  /** INFO-level logging for `message` with class name and {@link #getDagId()} */
  public void logContextualizedInfo(String message) {
    log.info(contextualizeStatus(message));
  }
}
