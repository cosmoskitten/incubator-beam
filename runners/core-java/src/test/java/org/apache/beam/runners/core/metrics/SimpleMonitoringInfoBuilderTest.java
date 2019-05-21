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

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.beam.model.pipeline.v1.MetricsApi;
import org.apache.beam.model.pipeline.v1.MetricsApi.MonitoringInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SimpleMonitoringInfoBuilder}. */
@RunWith(JUnit4.class)
public class SimpleMonitoringInfoBuilderTest {

  @Test
  public void testReturnsNullIfSpecRequirementsNotMet() {
    SimpleMonitoringInfoBuilder builder = new SimpleMonitoringInfoBuilder();
    builder.setUrn(MonitoringInfoConstants.Urns.ELEMENT_COUNT);
    assertNull(builder.build());

    builder.setInt64Value(1);
    assertNull(builder.build());
  }

  @Test
  public void testReturnsExpectedMonitoringInfo() {
    SimpleMonitoringInfoBuilder builder = new SimpleMonitoringInfoBuilder();
    builder.setUrn(MonitoringInfoConstants.Urns.ELEMENT_COUNT);

    builder.setInt64Value(1);
    builder.setLabel(MonitoringInfoConstants.Labels.PCOLLECTION, "myPcollection");
    // Pass now that the spec is fully met.
    MonitoringInfo monitoringInfo = builder.build();
    assertTrue(monitoringInfo != null);
    assertEquals(
        "myPcollection",
        monitoringInfo.getLabelsOrDefault(MonitoringInfoConstants.Labels.PCOLLECTION, null));
    assertEquals(MonitoringInfoConstants.Urns.ELEMENT_COUNT, monitoringInfo.getUrn());
    assertEquals(MonitoringInfoConstants.TypeUrns.SUM_INT64, monitoringInfo.getType());
    assertEquals(1, monitoringInfo.getMetric().getCounterData().getInt64Value());
    assertEquals(
        "myPcollection",
        monitoringInfo.getLabelsMap().get(MonitoringInfoConstants.Labels.PCOLLECTION));
  }

  @Test
  public void testUserDistribution() {
    SimpleMonitoringInfoBuilder builder = new SimpleMonitoringInfoBuilder();
    builder.setUrn(MonitoringInfoConstants.Urns.USER_DISTRIBUTION_COUNTER);
    builder.setLabel(MonitoringInfoConstants.Labels.NAME, "myName");
    builder.setLabel(MonitoringInfoConstants.Labels.NAMESPACE, "myNamespace");
    builder.setLabel(MonitoringInfoConstants.Labels.PTRANSFORM, "myStep");
    assertNull(builder.build());

    builder.setInt64DistributionValue(DistributionData.create(10, 2, 1, 9));
    // Pass now that the spec is fully met.
    MonitoringInfo monitoringInfo = builder.build();
    assertTrue(monitoringInfo != null);
    assertEquals(MonitoringInfoConstants.Urns.USER_DISTRIBUTION_COUNTER, monitoringInfo.getUrn());
    assertEquals(
        "myName", monitoringInfo.getLabelsOrDefault(MonitoringInfoConstants.Labels.NAME, ""));
    assertEquals(
        "myNamespace",
        monitoringInfo.getLabelsOrDefault(MonitoringInfoConstants.Labels.NAMESPACE, ""));
    assertEquals(MonitoringInfoConstants.TypeUrns.DISTRIBUTION_INT64, monitoringInfo.getType());
    MetricsApi.IntDistributionData distribution =
        monitoringInfo.getMetric().getDistributionData().getIntDistributionData();
    assertEquals(10, distribution.getSum());
    assertEquals(2, distribution.getCount());
    assertEquals(9, distribution.getMax());
    assertEquals(1, distribution.getMin());
  }
}
