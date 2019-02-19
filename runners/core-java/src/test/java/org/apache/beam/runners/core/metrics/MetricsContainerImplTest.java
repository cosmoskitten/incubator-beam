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

import static org.apache.beam.runners.core.metrics.MetricUpdateMatchers.metricUpdate;
import static org.apache.beam.runners.core.metrics.MetricUpdatesProtos.toProto;
import static org.apache.beam.runners.core.metrics.SimpleMonitoringInfoBuilder.clearTimestamp;
import static org.apache.beam.sdk.metrics.MetricUrns.ELEMENT_COUNT_URN;
import static org.apache.beam.sdk.metrics.MetricUrns.PCOLLECTION_LABEL;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo;
import org.apache.beam.sdk.metrics.MetricName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MetricsContainerImpl}. */
@RunWith(JUnit4.class)
public class MetricsContainerImplTest {

  @Test
  public void testCounterDeltas() {
    MetricsContainerImpl container = MetricsContainerImpl.ptransform("step1");
    CounterCell c1 = container.getCounter(MetricName.named("ns", "name1"));
    CounterCell c2 = container.getCounter(MetricName.named("ns", "name2"));
    assertThat(
        "All counters should start out dirty",
        container.getUpdates().counterUpdates(),
        containsInAnyOrder(metricUpdate("name1", 0L), metricUpdate("name2", 0L)));
    container.commitUpdates();
    assertThat(
        "After commit no counters should be dirty",
        container.getUpdates().counterUpdates(),
        emptyIterable());

    c1.inc(5L);
    c2.inc(4L);

    assertThat(
        container.getUpdates().counterUpdates(),
        containsInAnyOrder(metricUpdate("name1", 5L), metricUpdate("name2", 4L)));

    assertThat(
        "Since we haven't committed, updates are still included",
        container.getUpdates().counterUpdates(),
        containsInAnyOrder(metricUpdate("name1", 5L), metricUpdate("name2", 4L)));

    container.commitUpdates();
    assertThat(
        "After commit there are no updates",
        container.getUpdates().counterUpdates(),
        emptyIterable());

    c1.inc(8L);
    assertThat(container.getUpdates().counterUpdates(), contains(metricUpdate("name1", 13L)));

    CounterCell dne = container.tryGetCounter(MetricName.named("ns", "dne"));
    assertEquals(dne, null);
  }

  @Test
  public void testCounterCumulatives() {
    MetricsContainerImpl container = MetricsContainerImpl.ptransform("step1");
    CounterCell c1 = container.getCounter(MetricName.named("ns", "name1"));
    CounterCell c2 = container.getCounter(MetricName.named("ns", "name2"));
    c1.inc(2L);
    c2.inc(4L);
    c1.inc(3L);

    container.getUpdates();
    container.commitUpdates();
    assertThat(
        "Committing updates shouldn't affect cumulative counter values",
        container.getCumulative().counterUpdates(),
        containsInAnyOrder(metricUpdate("name1", 5L), metricUpdate("name2", 4L)));

    c1.inc(8L);
    assertThat(
        container.getCumulative().counterUpdates(),
        containsInAnyOrder(metricUpdate("name1", 13L), metricUpdate("name2", 4L)));

    CounterCell readC1 = container.tryGetCounter(MetricName.named("ns", "name1"));
    assertEquals(13L, (long) readC1.getCumulative());
  }

  @Test
  public void testDistributionDeltas() {
    MetricsContainerImpl container = MetricsContainerImpl.ptransform("step1");
    DistributionCell c1 = container.getDistribution(MetricName.named("ns", "name1"));
    DistributionCell c2 = container.getDistribution(MetricName.named("ns", "name2"));

    assertThat(
        "Initial update includes initial zero-values",
        container.getUpdates().distributionUpdates(),
        containsInAnyOrder(
            metricUpdate("name1", DistributionData.EMPTY),
            metricUpdate("name2", DistributionData.EMPTY)));

    container.commitUpdates();
    assertThat(
        "No updates after commit", container.getUpdates().distributionUpdates(), emptyIterable());

    c1.update(5L);
    c2.update(4L);

    assertThat(
        container.getUpdates().distributionUpdates(),
        containsInAnyOrder(
            metricUpdate("name1", DistributionData.create(5, 1, 5, 5)),
            metricUpdate("name2", DistributionData.create(4, 1, 4, 4))));
    assertThat(
        "Updates stay the same without commit",
        container.getUpdates().distributionUpdates(),
        containsInAnyOrder(
            metricUpdate("name1", DistributionData.create(5, 1, 5, 5)),
            metricUpdate("name2", DistributionData.create(4, 1, 4, 4))));

    container.commitUpdates();
    assertThat(
        "No updatess after commit", container.getUpdates().distributionUpdates(), emptyIterable());

    c1.update(8L);
    c1.update(4L);
    assertThat(
        container.getUpdates().distributionUpdates(),
        contains(metricUpdate("name1", DistributionData.create(17, 3, 4, 8))));
    container.commitUpdates();

    DistributionCell dne = container.tryGetDistribution(MetricName.named("ns", "dne"));
    assertEquals(dne, null);
  }

  @Test
  public void testMonitoringInfosArePopulatedForUserCounters() {
    MetricsContainerImpl testObject = MetricsContainerImpl.ptransform("step1");
    CounterCell c1 = testObject.getCounter(MetricName.named("ns", "name1"));
    CounterCell c2 = testObject.getCounter(MetricName.named("ns", "name2"));
    c1.inc(2L);
    c2.inc(4L);
    c1.inc(3L);

    SimpleMonitoringInfoBuilder builder1 = new SimpleMonitoringInfoBuilder();
    builder1.setUrnForUserMetric("ns", "name1");
    builder1.setInt64Value(5);
    builder1.setPTransformLabel("step1");
    builder1.build();

    SimpleMonitoringInfoBuilder builder2 = new SimpleMonitoringInfoBuilder();
    builder2.setUrnForUserMetric("ns", "name2");
    builder2.setInt64Value(4);
    builder2.setPTransformLabel("step1");
    builder2.build();

    ArrayList<MonitoringInfo> actualMonitoringInfos = new ArrayList<MonitoringInfo>();
    for (MonitoringInfo mi : toProto(testObject.getUpdates())) {
      actualMonitoringInfos.add(clearTimestamp(mi));
    }

    assertThat(actualMonitoringInfos, containsInAnyOrder(builder1.build(), builder2.build()));
  }

  @Test
  public void testMonitoringInfosArePopulatedForABeamCounter() {
    MetricsContainerImpl testObject = MetricsContainerImpl.ptransform("step1");
    HashMap<String, String> labels = new HashMap<String, String>();
    labels.put(PCOLLECTION_LABEL, "pcollection");
    MetricName name = MetricName.of(ELEMENT_COUNT_URN);
    CounterCell c1 = testObject.getCounter(name);
    c1.inc(2L);

    SimpleMonitoringInfoBuilder builder1 = new SimpleMonitoringInfoBuilder();
    builder1.setUrn(ELEMENT_COUNT_URN);
    builder1.setPCollectionLabel("pcollection");
    builder1.setInt64Value(2);
    builder1.build();

    ArrayList<MonitoringInfo> actualMonitoringInfos = new ArrayList<MonitoringInfo>();
    for (MonitoringInfo mi : toProto(testObject.getUpdates())) {
      actualMonitoringInfos.add(clearTimestamp(mi));
    }
    assertThat(actualMonitoringInfos, containsInAnyOrder(builder1.build()));
  }
}
