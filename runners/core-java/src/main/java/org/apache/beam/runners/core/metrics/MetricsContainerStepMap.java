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
package org.apache.beam.runners.core.metrics;

import static java.util.stream.Collectors.toList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.apache.beam.runners.core.metrics.MetricUpdates.MetricUpdate;
import org.apache.beam.sdk.metrics.MetricKey;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.labels.MetricLabels;

/**
 * Metrics containers by step.
 *
 * <p>This class is not thread-safe.
 */
public class MetricsContainerStepMap implements Serializable {
  private Map<MetricLabels, MetricsContainerImpl> metricsContainers;

  public MetricsContainerStepMap() {
    this.metricsContainers = new ConcurrentHashMap<>();
  }

  public MetricsContainerImpl ptransformContainer(String ptransform) {
    return getContainer(MetricLabels.ptransform(ptransform));
  }

  public MetricsContainerImpl pcollectionContainer(String pcollection) {
    return getContainer(MetricLabels.pcollection(pcollection));
  }

  /** Returns the container for the given step name. */
  public MetricsContainerImpl getContainer(MetricLabels labels) {
    if (!metricsContainers.containsKey(labels)) {
      metricsContainers.put(labels, new MetricsContainerImpl(labels));
    }
    return metricsContainers.get(labels);
  }

  /**
   * Update this {@link MetricsContainerStepMap} with all values from given {@link
   * MetricsContainerStepMap}.
   */
  public void updateAll(MetricsContainerStepMap other) {
    for (Map.Entry<MetricLabels, MetricsContainerImpl> container :
        other.metricsContainers.entrySet()) {
      getContainer(container.getKey()).update(container.getValue());
    }
  }

  /**
   * Update {@link MetricsContainerImpl} for given step in this map with all values from given
   * {@link MetricsContainerImpl}.
   */
  public void update(MetricLabels labels, MetricsContainerImpl container) {
    getContainer(labels).update(container);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    MetricsContainerStepMap that = (MetricsContainerStepMap) o;

    // TODO(BEAM-6546): The underlying MetricContainerImpls do not implement equals().
    return getMetricsContainers().equals(that.getMetricsContainers());
  }

  @Override
  public int hashCode() {
    return metricsContainers.hashCode();
  }

  /**
   * Returns {@link MetricResults} based on given {@link MetricsContainerStepMap} of attempted
   * metrics.
   *
   * <p>This constructor is intended for runners which only support `attempted` metrics. Accessing
   * {@link MetricResult#getCommitted()} in the resulting {@link MetricResults} will result in an
   * {@link UnsupportedOperationException}.
   */
  public static MetricResults asAttemptedOnlyMetricResults(
      MetricsContainerStepMap attemptedMetricsContainers) {
    return asMetricResults(attemptedMetricsContainers, new MetricsContainerStepMap());
  }

  /**
   * Returns {@link MetricResults} based on given {@link MetricsContainerStepMap
   * MetricsContainerStepMaps} of attempted and committed metrics.
   *
   * <p>This constructor is intended for runners which support both attempted and committed metrics.
   */
  public static MetricResults asMetricResults(
      MetricsContainerStepMap attemptedMetricsContainers,
      MetricsContainerStepMap committedMetricsContainers) {
    Map<MetricKey, MetricResult<Long>> counters = new HashMap<>();
    Map<MetricKey, MetricResult<DistributionData>> distributions = new HashMap<>();
    Map<MetricKey, MetricResult<GaugeData>> gauges = new HashMap<>();

    for (MetricsContainerImpl container : attemptedMetricsContainers.getMetricsContainers()) {
      MetricUpdates cumulative = container.getCumulative();
      mergeAttemptedResults(counters, cumulative.counterUpdates(), (l, r) -> l + r);
      mergeAttemptedResults(
          distributions, cumulative.distributionUpdates(), DistributionData::combine);
      mergeAttemptedResults(gauges, cumulative.gaugeUpdates(), GaugeData::combine);
    }
    for (MetricsContainerImpl container : committedMetricsContainers.getMetricsContainers()) {
      MetricUpdates cumulative = container.getCumulative();
      mergeCommittedResults(counters, cumulative.counterUpdates(), (l, r) -> l + r);
      mergeCommittedResults(
          distributions, cumulative.distributionUpdates(), DistributionData::combine);
      mergeCommittedResults(gauges, cumulative.gaugeUpdates(), GaugeData::combine);
    }

    return new DefaultMetricResults(
        counters.values(),
        distributions.values().stream()
            .map(result -> result.transform(DistributionData::extractResult))
            .collect(toList()),
        gauges.values().stream()
            .map(result -> result.transform(GaugeData::extractResult))
            .collect(toList()));
  }

  /** Return the cumulative values for any metrics in this container as MonitoringInfos. */
  public Iterable<MonitoringInfo> getMonitoringInfos() {
    // Extract user metrics and store as MonitoringInfos.
    ArrayList<MonitoringInfo> monitoringInfos = new ArrayList<>();
    for (MetricsContainerImpl container : getMetricsContainers()) {
      for (MonitoringInfo mi : container.getMonitoringInfos()) {
        monitoringInfos.add(mi);
      }
    }
    return monitoringInfos;
  }

  @Override
  public String toString() {
    return asAttemptedOnlyMetricResults(this).toString();
  }

  private Iterable<MetricsContainerImpl> getMetricsContainers() {
    return metricsContainers.values();
  }

  @SuppressWarnings("ConstantConditions")
  private static <T> void mergeAttemptedResults(
      Map<MetricKey, MetricResult<T>> metricResultMap,
      Iterable<MetricUpdate<T>> updates,
      BiFunction<T, T, T> combine) {
    for (MetricUpdate<T> metricUpdate : updates) {
      MetricKey key = metricUpdate.getKey();
      MetricResult<T> current = metricResultMap.get(key);
      if (current == null) {
        metricResultMap.put(key, MetricResult.attempted(key, metricUpdate.getUpdate()));
      } else {
        metricResultMap.put(key, current.addAttempted(metricUpdate.getUpdate(), combine));
      }
    }
  }

  private static <T> void mergeCommittedResults(
      Map<MetricKey, MetricResult<T>> metricResultMap,
      Iterable<MetricUpdate<T>> updates,
      BiFunction<T, T, T> combine) {
    for (MetricUpdate<T> metricUpdate : updates) {
      MetricKey key = metricUpdate.getKey();
      MetricResult<T> current = metricResultMap.get(key);
      if (current == null) {
        throw new IllegalStateException(
            String.format(
                "%s: existing 'attempted' result not found for 'committed' value %s",
                key, metricUpdate.getUpdate()));
      }
      metricResultMap.put(key, current.addCommitted(metricUpdate.getUpdate(), combine));
    }
  }
}
