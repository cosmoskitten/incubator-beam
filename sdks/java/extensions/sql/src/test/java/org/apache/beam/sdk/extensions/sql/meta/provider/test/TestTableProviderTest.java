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
package org.apache.beam.sdk.extensions.sql.meta.provider.test;

import org.apache.beam.sdk.extensions.sql.meta.BeamSqlTable;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_20_0.com.google.common.collect.ImmutableList;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestTableProviderTest {
  private static final Schema BASIC_SCHEMA =
      Schema.builder().addInt32Field("id").addStringField("name").build();

  @Rule public TestPipeline pipeline = TestPipeline.create();

  @Test
  public void testInMemoryTableProviderWithProjectPushDown() {
    TestTableProvider tableProvider = new TestTableProvider();
    Table table = getTable("tableName");
    tableProvider.createTable(table);
    tableProvider.addRows(
        table.getName(), row(BASIC_SCHEMA, 1, "one"), row(BASIC_SCHEMA, 2, "two"));

    BeamSqlTable beamSqlTable = tableProvider.buildBeamSqlTable(table);

    PCollection<Row> result =
        beamSqlTable.buildIOReader(pipeline.begin(), null, ImmutableList.of("name"));

    PAssert.that(result)
        .containsInAnyOrder(row(result.getSchema(), "one"), row(result.getSchema(), "two"));

    pipeline.run().waitUntilFinish(Duration.standardMinutes(2));
  }

  private static Row row(Schema schema, Object... objects) {
    return Row.withSchema(schema).addValues(objects).build();
  }

  private static Table getTable(String name) {
    return Table.builder()
        .name(name)
        .comment(name + " table")
        .schema(BASIC_SCHEMA)
        .type("test")
        .build();
  }
}
