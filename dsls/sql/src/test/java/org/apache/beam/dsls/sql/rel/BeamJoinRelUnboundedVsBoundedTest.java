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

package org.apache.beam.dsls.sql.rel;

import static org.apache.beam.dsls.sql.TestUtils.beamSqlRows2Strings;

import java.util.Date;

import org.apache.beam.dsls.sql.BeamSQLEnvironment;
import org.apache.beam.dsls.sql.TestUtils;
import org.apache.beam.dsls.sql.exception.BeamSqlUnsupportedException;
import org.apache.beam.dsls.sql.planner.MockedBeamSQLTable;
import org.apache.beam.dsls.sql.schema.BeamSQLRow;
import org.apache.beam.dsls.sql.transform.BeamSQLOutputToConsoleFn;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Unbounded + Unbounded Test for {@code BeamJoinRel}.
 */
public class BeamJoinRelUnboundedVsBoundedTest {
  @Rule
  public final TestPipeline pipeline = TestPipeline.create();
  public static BeamSQLEnvironment runner = BeamSQLEnvironment.create();
  public static final Date FIRST_DATE = new Date();
  public static final Date SECOND_DATE = new Date();
  public static final Date THIRD_DATE = new Date();

  @BeforeClass
  public static void prepare() {
    FIRST_DATE.setTime(1);
    SECOND_DATE.setTime(1 + 3600 * 1000);
    THIRD_DATE.setTime(1 + 3600 * 1000 + 3600 * 1000 + 1);
    runner.addTableMetadata("ORDER_DETAILS", MockedBeamSQLTable
        .of(SqlTypeName.INTEGER, "order_id",
            SqlTypeName.INTEGER, "site_id",
            SqlTypeName.INTEGER, "price",
            SqlTypeName.TIMESTAMP, "order_time",

            1, 1, 1, FIRST_DATE,
            1, 2, 2, FIRST_DATE,
            2, 2, 3, SECOND_DATE,
            2, 3, 3, SECOND_DATE,
            3, 3, 3, THIRD_DATE
            ).withIsBounded(PCollection.IsBounded.UNBOUNDED));

    runner.addTableMetadata("ORDER_DETAILS1", MockedBeamSQLTable
        .of(SqlTypeName.INTEGER, "order_id",
            SqlTypeName.VARCHAR, "buyer",

            1, "james",
            2, "bond"
        ).withIsBounded(PCollection.IsBounded.BOUNDED));
  }

  @Test
  public void testInnerJoin() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " JOIN "
        + " ORDER_DETAILS1 o2 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;

    PCollection<BeamSQLRow> rows = runner.compileBeamPipeline(sql, pipeline);
    PAssert.that(rows.apply(ParDo.of(new TestUtils.BeamSqlRow2StringDoFn())))
        .containsInAnyOrder(beamSqlRows2Strings(MockedBeamSQLTable.of(
        SqlTypeName.INTEGER, "order_id",
        SqlTypeName.INTEGER, "sum_site_id",
        SqlTypeName.VARCHAR, "buyer",
        1, 3, "james",
        2, 5, "bond"
        ).getInputRecords()));
    pipeline.run();
  }

  @Test
  public void testLeftOuterJoin() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " LEFT OUTER JOIN "
        + " ORDER_DETAILS1 o2 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;

    PCollection<BeamSQLRow> rows = runner.compileBeamPipeline(sql, pipeline);
    rows.apply(ParDo.of(new BeamSQLOutputToConsoleFn("helloworld")));
    PAssert.that(rows.apply(ParDo.of(new TestUtils.BeamSqlRow2StringDoFn())))
        .containsInAnyOrder(beamSqlRows2Strings(MockedBeamSQLTable.of(
            SqlTypeName.INTEGER, "order_id",
            SqlTypeName.INTEGER, "sum_site_id",
            SqlTypeName.VARCHAR, "buyer",
            1, 3, "james",
            2, 5, "bond",
            3, 3, null
        ).getInputRecords()));
    pipeline.run();
  }

  @Test(expected = BeamSqlUnsupportedException.class)
  public void testLeftOuterJoinError() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + " ORDER_DETAILS1 o2 "
        + " LEFT OUTER JOIN "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;
    pipeline.enableAbandonedNodeEnforcement(false);
    runner.compileBeamPipeline(sql, pipeline);
    pipeline.run();
  }

  @Test
  public void testRightOuterJoin() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + " ORDER_DETAILS1 o2 "
        + " RIGHT OUTER JOIN "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;
    PCollection<BeamSQLRow> rows = runner.compileBeamPipeline(sql, pipeline);
    PAssert.that(rows.apply(ParDo.of(new TestUtils.BeamSqlRow2StringDoFn())))
        .containsInAnyOrder(beamSqlRows2Strings(MockedBeamSQLTable.of(
            SqlTypeName.INTEGER, "order_id",
            SqlTypeName.INTEGER, "sum_site_id",
            SqlTypeName.VARCHAR, "buyer",
            1, 3, "james",
            2, 5, "bond",
            3, 3, null
        ).getInputRecords()));
    pipeline.run();
  }

  @Test(expected = BeamSqlUnsupportedException.class)
  public void testRightOuterJoinError() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " RIGHT OUTER JOIN "
        + " ORDER_DETAILS1 o2 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;

    pipeline.enableAbandonedNodeEnforcement(false);
    runner.compileBeamPipeline(sql, pipeline);
    pipeline.run();
  }

  @Test(expected = BeamSqlUnsupportedException.class)
  public void testFullOuterJoinError() throws Exception {
    String sql = "SELECT o1.order_id, o1.sum_site_id, o2.buyer FROM "
        + " ORDER_DETAILS1 o2 "
        + " FULL OUTER JOIN "
        + "(select order_id, sum(site_id) as sum_site_id FROM ORDER_DETAILS "
        + "          GROUP BY order_id, TUMBLE(order_time, INTERVAL '1' HOUR)) o1 "
        + " on "
        + " o1.order_id=o2.order_id"
        ;
    pipeline.enableAbandonedNodeEnforcement(false);
    runner.compileBeamPipeline(sql, pipeline);
    pipeline.run();
  }
}
