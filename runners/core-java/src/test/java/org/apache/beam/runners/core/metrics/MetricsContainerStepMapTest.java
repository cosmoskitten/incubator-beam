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
import static java.util.stream.StreamSupport.stream;
import static org.apache.beam.runners.core.metrics.MetricsContainerStepMap.asAttemptedOnlyMetricResults;
import static org.apache.beam.runners.core.metrics.MetricsContainerStepMap.asMetricResults;
import static org.apache.beam.sdk.metrics.MetricResultsMatchers.metricsResult;
import static org.apache.beam.sdk.metrics.SimpleMonitoringInfoBuilder.ELEMENT_COUNT_URN;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.CounterCell;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.DistributionResult;
import org.apache.beam.sdk.metrics.Gauge;
import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.metrics.SimpleMonitoringInfoBuilder;
import org.apache.beam.sdk.metrics.labels.MetricLabels;
import org.hamcrest.collection.IsIterableWithSize;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tests for {@link MetricsContainerStepMap}. */
public class MetricsContainerStepMapTest {
  private static final Logger LOG = LoggerFactory.getLogger(MetricsContainerStepMapTest.class);

  private static final String NAMESPACE = MetricsContainerStepMapTest.class.getName();
  private static final String UNUSED_STEP = "unusedStep";
  private static final String STEP1 = "myStep1";
  private static final String STEP2 = "myStep2";
  private static final String COUNTER_NAME = "myCounter";
  private static final String DISTRIBUTION_NAME = "myDistribution";
  private static final String GAUGE_NAME = "myGauge";

  private static final long VALUE = 100;

  private static final Counter counter =
      Metrics.counter(MetricsContainerStepMapTest.class, COUNTER_NAME);
  private static final Distribution distribution =
      Metrics.distribution(MetricsContainerStepMapTest.class, DISTRIBUTION_NAME);
  private static final Gauge gauge = Metrics.gauge(MetricsContainerStepMapTest.class, GAUGE_NAME);

  private static final MetricsContainerImpl metricsContainer;

  static {
    metricsContainer = MetricsContainerImpl.ptransform(UNUSED_STEP);
    try (Closeable ignored = MetricsEnvironment.scopedMetricsContainer(metricsContainer)) {
      counter.inc(VALUE);
      distribution.update(VALUE);
      distribution.update(VALUE * 2);
      gauge.set(VALUE);
    } catch (IOException e) {
      LOG.error(e.getMessage(), e);
    }
  }

  @Rule public transient ExpectedException thrown = ExpectedException.none();

  @Test
  public void testAttemptedAccumulatedMetricResults() {
    MetricsContainerStepMap attemptedMetrics = new MetricsContainerStepMap();
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);

    MetricResults metricResults = asAttemptedOnlyMetricResults(attemptedMetrics);

    MetricQueryResults step1res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP1));

    assertIterableSize(step1res.getCounters(), 1);
    assertIterableSize(step1res.getDistributions(), 1);
    assertIterableSize(step1res.getGauges(), 1);

    assertCounter(COUNTER_NAME, step1res, STEP1, VALUE, false);
    assertDistribution(
        DISTRIBUTION_NAME,
        step1res,
        STEP1,
        DistributionResult.create(VALUE * 3, 2, VALUE, VALUE * 2),
        false);
    assertGauge(GAUGE_NAME, step1res, STEP1, GaugeResult.create(VALUE, Instant.now()), false);

    MetricQueryResults step2res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP2));

    assertIterableSize(step2res.getCounters(), 1);
    assertIterableSize(step2res.getDistributions(), 1);
    assertIterableSize(step2res.getGauges(), 1);

    assertCounter(COUNTER_NAME, step2res, STEP2, VALUE * 2, false);
    assertDistribution(
        DISTRIBUTION_NAME,
        step2res,
        STEP2,
        DistributionResult.create(VALUE * 6, 4, VALUE, VALUE * 2),
        false);
    assertGauge(GAUGE_NAME, step2res, STEP2, GaugeResult.create(VALUE, Instant.now()), false);

    MetricQueryResults allres = metricResults.allMetrics();

    assertIterableSize(allres.getCounters(), 2);
    assertIterableSize(allres.getDistributions(), 2);
    assertIterableSize(allres.getGauges(), 2);
  }

  @Test
  public void testCounterCommittedUnsupportedInAttemptedAccumulatedMetricResults() {
    MetricsContainerStepMap attemptedMetrics = new MetricsContainerStepMap();
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    MetricResults metricResults = asAttemptedOnlyMetricResults(attemptedMetrics);

    MetricQueryResults step1res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP1));

    thrown.expect(UnsupportedOperationException.class);
    thrown.expectMessage("This runner does not currently support committed metrics results.");

    assertCounter(COUNTER_NAME, step1res, STEP1, VALUE, true);
  }

  @Test
  public void testDistributionCommittedUnsupportedInAttemptedAccumulatedMetricResults() {
    MetricsContainerStepMap attemptedMetrics = new MetricsContainerStepMap();
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    MetricResults metricResults = asAttemptedOnlyMetricResults(attemptedMetrics);

    MetricQueryResults step1res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP1));

    thrown.expect(UnsupportedOperationException.class);
    thrown.expectMessage("This runner does not currently support committed metrics results.");

    assertDistribution(
        DISTRIBUTION_NAME, step1res, STEP1, DistributionResult.IDENTITY_ELEMENT, true);
  }

  @Test
  public void testGaugeCommittedUnsupportedInAttemptedAccumulatedMetricResults() {
    MetricsContainerStepMap attemptedMetrics = new MetricsContainerStepMap();
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    MetricResults metricResults = asAttemptedOnlyMetricResults(attemptedMetrics);

    MetricQueryResults step1res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP1));

    thrown.expect(UnsupportedOperationException.class);
    thrown.expectMessage("This runner does not currently support committed metrics results.");

    assertGauge(GAUGE_NAME, step1res, STEP1, GaugeResult.empty(), true);
  }

  @Test
  public void testUpdateAllUpdatesUnboundedAndBoundedContainers() {
    MetricsContainerStepMap baseMetricContainerRegistry = new MetricsContainerStepMap();

    CounterCell c1 =
        baseMetricContainerRegistry
            .getContainer(MetricLabels.ptransform(STEP1))
            .getCounter(MetricName.named("ns", "name1"));

    CounterCell c2 =
        baseMetricContainerRegistry
            .getContainer(MetricLabels.pcollection("testPCollection"))
            .getCounter(MetricName.of(ELEMENT_COUNT_URN));

    c1.inc(7);
    c2.inc(14);

    MetricsContainerStepMap testObject = new MetricsContainerStepMap();
    testObject.updateAll(baseMetricContainerRegistry);

    MonitoringInfo[] expected = {
      new SimpleMonitoringInfoBuilder().userMetric(STEP1, "ns", "name1").setInt64Value(7).build(),
      new SimpleMonitoringInfoBuilder().forElementCount("testPCollection").setInt64Value(14).build()
    };

    List<MonitoringInfo> actual =
        stream(testObject.getMonitoringInfos().spliterator(), false)
            .map(SimpleMonitoringInfoBuilder::clearTimestamp)
            .collect(toList());

    assertThat(actual, containsInAnyOrder(expected));
  }

  @Test
  public void testAttemptedAndCommittedAccumulatedMetricResults() {
    MetricsContainerStepMap attemptedMetrics = new MetricsContainerStepMap();
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);
    attemptedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);

    MetricsContainerStepMap committedMetrics = new MetricsContainerStepMap();
    committedMetrics.update(MetricLabels.ptransform(STEP1), metricsContainer);
    committedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);
    committedMetrics.update(MetricLabels.ptransform(STEP2), metricsContainer);

    MetricResults metricResults = asMetricResults(attemptedMetrics, committedMetrics);

    MetricQueryResults step1res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP1));

    assertIterableSize(step1res.getCounters(), 1);
    assertIterableSize(step1res.getDistributions(), 1);
    assertIterableSize(step1res.getGauges(), 1);

    assertCounter(COUNTER_NAME, step1res, STEP1, VALUE * 2, false);
    assertDistribution(
        DISTRIBUTION_NAME,
        step1res,
        STEP1,
        DistributionResult.create(VALUE * 6, 4, VALUE, VALUE * 2),
        false);
    assertGauge(GAUGE_NAME, step1res, STEP1, GaugeResult.create(VALUE, Instant.now()), false);

    assertCounter(COUNTER_NAME, step1res, STEP1, VALUE, true);
    assertDistribution(
        DISTRIBUTION_NAME,
        step1res,
        STEP1,
        DistributionResult.create(VALUE * 3, 2, VALUE, VALUE * 2),
        true);
    assertGauge(GAUGE_NAME, step1res, STEP1, GaugeResult.create(VALUE, Instant.now()), true);

    MetricQueryResults step2res = metricResults.queryMetrics(MetricsFilter.ptransform(STEP2));

    assertIterableSize(step2res.getCounters(), 1);
    assertIterableSize(step2res.getDistributions(), 1);
    assertIterableSize(step2res.getGauges(), 1);

    assertCounter(COUNTER_NAME, step2res, STEP2, VALUE * 3, false);
    assertDistribution(
        DISTRIBUTION_NAME,
        step2res,
        STEP2,
        DistributionResult.create(VALUE * 9, 6, VALUE, VALUE * 2),
        false);
    assertGauge(GAUGE_NAME, step2res, STEP2, GaugeResult.create(VALUE, Instant.now()), false);

    assertCounter(COUNTER_NAME, step2res, STEP2, VALUE * 2, true);
    assertDistribution(
        DISTRIBUTION_NAME,
        step2res,
        STEP2,
        DistributionResult.create(VALUE * 6, 4, VALUE, VALUE * 2),
        true);
    assertGauge(GAUGE_NAME, step2res, STEP2, GaugeResult.create(VALUE, Instant.now()), true);

    MetricQueryResults allres = metricResults.allMetrics();

    assertIterableSize(allres.getCounters(), 2);
    assertIterableSize(allres.getDistributions(), 2);
    assertIterableSize(allres.getGauges(), 2);
  }

  private <T> void assertIterableSize(Iterable<T> iterable, int size) {
    assertThat(iterable, IsIterableWithSize.iterableWithSize(size));
  }

  private void assertCounter(
      String name,
      MetricQueryResults metricQueryResults,
      String step,
      Long expected,
      boolean isCommitted) {
    assertThat(
        metricQueryResults.getCounters(),
        hasItem(metricsResult(NAMESPACE, name, step, expected, isCommitted)));
  }

  private void assertDistribution(
      String name,
      MetricQueryResults metricQueryResults,
      String step,
      DistributionResult expected,
      boolean isCommitted) {
    assertThat(
        metricQueryResults.getDistributions(),
        hasItem(metricsResult(NAMESPACE, name, step, expected, isCommitted)));
  }

  private void assertGauge(
      String name,
      MetricQueryResults metricQueryResults,
      String step,
      GaugeResult expected,
      boolean isCommitted) {
    assertThat(
        metricQueryResults.getGauges(),
        hasItem(metricsResult(NAMESPACE, name, step, expected, isCommitted)));
  }
}
