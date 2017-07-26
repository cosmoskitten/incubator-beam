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
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.beam.dsls.sql.schema.BeamIOType;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.BeamRow;
import org.apache.beam.sdk.values.BeamRowType;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;

/**
 * Mocked table for bounded data sources.
 */
public class MockedBoundedTable extends MockedTable {
  /** rows written to this table. */
  private static final ConcurrentLinkedQueue<BeamRow> CONTENT = new ConcurrentLinkedQueue<>();
  /** rows flow out from this table. */
  private final List<BeamRow> rows = new ArrayList<>();

  public MockedBoundedTable(BeamRowType beamSqlRowType) {
    super(beamSqlRowType);
  }

  /**
   * Convenient way to build a mocked bounded table.
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
  public static MockedBoundedTable of(final Object... args){
    return new MockedBoundedTable(buildBeamSqlRowType(args));
  }

  /**
   * Build a mocked bounded table with the specified type.
   */
  public static MockedBoundedTable of(final BeamRowType type) {
    return new MockedBoundedTable(type);
  }


  /**
   * Add rows to the builder.
   *
   * <p>Sample usage:
   *
   * <pre>{@code
   * addRows(
   *   1, 3, "james", -- first row
   *   2, 5, "bond"   -- second row
   *   ...
   * )
   * }</pre>
   */
  public MockedBoundedTable addRows(Object... args) {
    List<BeamRow> rows = buildRows(getRowType(), Arrays.asList(args));
    this.rows.addAll(rows);
    return this;
  }

  @Override
  public BeamIOType getSourceType() {
    return BeamIOType.BOUNDED;
  }

  @Override
  public PCollection<BeamRow> buildIOReader(Pipeline pipeline) {
    return PBegin.in(pipeline).apply(
        "MockedBoundedTable_Reader_" + COUNTER.incrementAndGet(), Create.of(rows));
  }

  @Override public PTransform<? super PCollection<BeamRow>, PDone> buildIOWriter() {
    return new OutputStore();
  }

  /**
   * Keep output in {@code CONTENT} for validation.
   *
   */
  public static class OutputStore extends PTransform<PCollection<BeamRow>, PDone> {

    @Override
    public PDone expand(PCollection<BeamRow> input) {
      input.apply(ParDo.of(new DoFn<BeamRow, Void>() {
        @ProcessElement
        public void processElement(ProcessContext c) {
          CONTENT.add(c.element());
        }

        @Teardown
        public void close() {
          CONTENT.clear();
        }

      }));
      return PDone.in(input.getPipeline());
    }
  }
}
