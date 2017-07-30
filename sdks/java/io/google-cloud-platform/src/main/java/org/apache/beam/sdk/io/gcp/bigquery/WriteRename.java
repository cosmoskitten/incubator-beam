/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfigurationTableCopy;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers.Status;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.DatasetService;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.JobService;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies temporary tables to destination table.
 */
class WriteRename extends DoFn<KV<TableDestination, Iterable<String>>, Void> {
  private static final Logger LOG = LoggerFactory.getLogger(WriteRename.class);

  private final BigQueryServices bqServices;
  private final PCollectionView<String> jobIdToken;
  private final WriteDisposition firstPaneWriteDisposition;
  private final CreateDisposition firstPaneCreateDisposition;
  // Map from final destination to a list of temporary tables that need to be copied into it.
  @Nullable
  private final PCollectionView<Map<TableDestination, Iterable<String>>> tempTablesView;


  public WriteRename(
      BigQueryServices bqServices,
      PCollectionView<String> jobIdToken,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      @Nullable
      PCollectionView<Map<TableDestination, Iterable<String>>> tempTablesView) {
    this.bqServices = bqServices;
    this.jobIdToken = jobIdToken;
    this.firstPaneWriteDisposition = writeDisposition;
    this.firstPaneCreateDisposition = createDisposition;
    this.tempTablesView = tempTablesView;
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    if (tempTablesView != null) {
      Map<TableDestination, Iterable<String>> tempTablesMap =
          Maps.newHashMap(c.sideInput(tempTablesView));

      // Process each destination table.
      for (Map.Entry<TableDestination, Iterable<String>> entry : tempTablesMap.entrySet()) {
        writeRename(entry.getKey(), entry.getValue(), c);
      }
    } else {
      writeRename(c.element().getKey(), c.element().getValue(), c);
    }
  }

  private void writeRename(TableDestination finalTableDestination,
                           Iterable<String> tempTableNames,
                           ProcessContext c) throws Exception {
    WriteDisposition writeDisposition =
        (c.pane().getIndex() == 0) ? firstPaneWriteDisposition : WriteDisposition.WRITE_APPEND;
    CreateDisposition createDisposition =
        (c.pane().getIndex() == 0) ? firstPaneCreateDisposition : CreateDisposition.CREATE_NEVER;
    List<String> tempTablesJson = Lists.newArrayList(tempTableNames);
    // Do not copy if no temp tables are provided
    if (tempTablesJson.size() == 0) {
      return;
    }

    List<TableReference> tempTables = Lists.newArrayList();
    for (String table : tempTablesJson) {
      tempTables.add(BigQueryHelpers.fromJsonString(table, TableReference.class));
    }

    // Make sure each destination table gets a unique job id.
    String jobIdPrefix = BigQueryHelpers.createJobId(
        c.sideInput(jobIdToken), finalTableDestination, -1);

    copy(
        bqServices.getJobService(c.getPipelineOptions().as(BigQueryOptions.class)),
        bqServices.getDatasetService(c.getPipelineOptions().as(BigQueryOptions.class)),
        jobIdPrefix,
        finalTableDestination.getTableReference(),
        tempTables,
        writeDisposition,
        createDisposition,
        finalTableDestination.getTableDescription());

    DatasetService tableService =
        bqServices.getDatasetService(c.getPipelineOptions().as(BigQueryOptions.class));
    removeTemporaryTables(tableService, tempTables);
  }

  private void copy(
      JobService jobService,
      DatasetService datasetService,
      String jobIdPrefix,
      TableReference ref,
      List<TableReference> tempTables,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      @Nullable String tableDescription) throws InterruptedException, IOException {
    JobConfigurationTableCopy copyConfig = new JobConfigurationTableCopy()
        .setSourceTables(tempTables)
        .setDestinationTable(ref)
        .setWriteDisposition(writeDisposition.name())
        .setCreateDisposition(createDisposition.name());

    String projectId = ref.getProjectId();
    Job lastFailedCopyJob = null;
    for (int i = 0; i < BatchLoads.MAX_RETRY_JOBS; ++i) {
      String jobId = jobIdPrefix + "-" + i;
      JobReference jobRef = new JobReference()
          .setProjectId(projectId)
          .setJobId(jobId);
      jobService.startCopyJob(jobRef, copyConfig);
      Job copyJob = jobService.pollJob(jobRef, BatchLoads.LOAD_JOB_POLL_MAX_RETRIES);
      Status jobStatus = BigQueryHelpers.parseStatus(copyJob);
      switch (jobStatus) {
        case SUCCEEDED:
          if (tableDescription != null) {
            datasetService.patchTableDescription(ref, tableDescription);
          }
          return;
        case UNKNOWN:
          throw new RuntimeException(String.format(
              "UNKNOWN status of copy job [%s]: %s.", jobId,
              BigQueryHelpers.jobToPrettyString(copyJob)));
        case FAILED:
          lastFailedCopyJob = copyJob;
          continue;
        default:
          throw new IllegalStateException(String.format(
              "Unexpected status [%s] of load job: %s.",
              jobStatus, BigQueryHelpers.jobToPrettyString(copyJob)));
      }
    }
    throw new RuntimeException(String.format(
        "Failed to create copy job with id prefix %s, "
            + "reached max retries: %d, last failed copy job: %s.",
        jobIdPrefix,
        BatchLoads.MAX_RETRY_JOBS,
        BigQueryHelpers.jobToPrettyString(lastFailedCopyJob)));
  }

  static void removeTemporaryTables(DatasetService tableService,
      List<TableReference> tempTables) {
    for (TableReference tableRef : tempTables) {
      try {
        LOG.debug("Deleting table {}", BigQueryHelpers.toJsonString(tableRef));
        tableService.deleteTable(tableRef);
      } catch (Exception e) {
        LOG.warn("Failed to delete the table {}", BigQueryHelpers.toJsonString(tableRef), e);
      }
    }
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);

    builder
        .add(DisplayData.item("firstPaneWriteDisposition", firstPaneWriteDisposition.toString())
            .withLabel("Write Disposition"))
        .add(DisplayData.item("firstPaneCreateDisposition", firstPaneCreateDisposition.toString())
            .withLabel("Create Disposition"));
  }
}
