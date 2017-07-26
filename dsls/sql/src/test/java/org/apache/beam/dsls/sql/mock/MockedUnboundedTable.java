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

package org.apache.beam.dsls.sql.mock;

import static org.apache.beam.dsls.sql.TestUtils.buildBeamSqlRowType;
import static org.apache.beam.dsls.sql.TestUtils.buildRows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.beam.dsls.sql.schema.BeamIOType;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.BeamRowCoder;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.values.BeamRow;
import org.apache.beam.sdk.values.BeamRowType;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.calcite.util.Pair;
import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * A mocked unbounded table.
 */
public class MockedUnboundedTable extends MockedTable {
  /** rows flow out from this table with the specified watermark instant. */
  private final List<Pair<Duration, List<BeamRow>>> timestampedRows = new ArrayList<>();
  /** specify the index of column in the row which stands for the event time field. */
  private int timestampField;
  private MockedUnboundedTable(BeamRowType beamSqlRowType) {
    super(beamSqlRowType);
  }

  /**
   * Convenient way to build a mocked unbounded table.
   *
   * <p>e.g.
   *
   * <pre>{@code
   * MockedUnboundedTable
   *   .of(Types.BIGINT, "order_id",
   *       Types.INTEGER, "site_id",
   *       Types.DOUBLE, "price",
   *       Types.TIMESTAMP, "order_time")
   * }</pre>
   */
  public static MockedUnboundedTable of(final Object... args){
    return new MockedUnboundedTable(buildBeamSqlRowType(args));
  }

  public MockedUnboundedTable timestampColumnIndex(int idx) {
    this.timestampField = idx;
    return this;
  }

  /**
   * Add rows to the builder.
   *
   * <p>Sample usage:
   *
   * <pre>{@code
   * addRows(
   *   duration,      -- duration which stands for the corresponding watermark instant
   *   1, 3, "james", -- first row
   *   2, 5, "bond"   -- second row
   *   ...
   * )
   * }</pre>
   */
  public MockedUnboundedTable addRows(Duration duration, Object... args) {
    List<BeamRow> rows = buildRows(getRowType(), Arrays.asList(args));
    // record the watermark + rows
    this.timestampedRows.add(Pair.of(duration, rows));
    return this;
  }

  @Override public BeamIOType getSourceType() {
    return BeamIOType.UNBOUNDED;
  }

  @Override public PCollection<BeamRow> buildIOReader(Pipeline pipeline) {
    TestStream.Builder<BeamRow> values = TestStream.create(
        new BeamRowCoder(beamSqlRowType));

    for (Pair<Duration, List<BeamRow>> pair : timestampedRows) {
      values = values.advanceWatermarkTo(new Instant(0).plus(pair.getKey()));
      for (int i = 0; i < pair.getValue().size(); i++) {
        values = values.addElements(TimestampedValue.of(pair.getValue().get(i),
            new Instant(pair.getValue().get(i).getDate(timestampField))));
      }
    }

    return pipeline.begin().apply(
        "MockedUnboundedTable_" + COUNTER.incrementAndGet(),
        values.advanceWatermarkToInfinity());
  }
}
