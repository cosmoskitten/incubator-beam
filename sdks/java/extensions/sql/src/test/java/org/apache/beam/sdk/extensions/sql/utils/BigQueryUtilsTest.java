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

package org.apache.beam.sdk.extensions.sql.utils;

import static org.apache.beam.sdk.extensions.sql.utils.BigQueryUtils.toTableRow;
import static org.apache.beam.sdk.extensions.sql.utils.BigQueryUtils.toTableSchema;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.Assert.assertThat;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.bigquery.Field.Mode;
import com.google.cloud.bigquery.StandardSQLTypeName;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.apache.beam.sdk.extensions.sql.RowSqlType;
import org.apache.beam.sdk.extensions.sql.SqlTypeCoders;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.RowType;
import org.junit.Test;

/**
 * Tests for {@link BigQueryUtils}.
 */
public class BigQueryUtilsTest {
  private static final RowType FLAT_TYPE = RowSqlType
      .builder()
      .withBigIntField("id")
      .withDoubleField("value")
      .withVarcharField("name")
      .withTimestampField("timestamp")
      .withBooleanField("valid")
      .build();

  private static final RowType ARRAY_TYPE = RowSqlType
      .builder()
      .withArrayField("ids", SqlTypeCoders.BIGINT)
      .build();

  private static final RowType ROW_TYPE = RowSqlType
      .builder()
      .withRowField("row", FLAT_TYPE)
      .build();

  private static final RowType ARRAY_ROW_TYPE = RowSqlType
      .builder()
      .withArrayField("rows", FLAT_TYPE)
      .build();

  private static final TableFieldSchema ID =
      new TableFieldSchema().setName("id")
        .setType(StandardSQLTypeName.INT64.toString());

  private static final TableFieldSchema VALUE =
      new TableFieldSchema().setName("value")
        .setType(StandardSQLTypeName.FLOAT64.toString());

  private static final TableFieldSchema NAME =
      new TableFieldSchema().setName("name")
        .setType(StandardSQLTypeName.STRING.toString());

  private static final TableFieldSchema TIMESTAMP =
      new TableFieldSchema().setName("timestamp")
        .setType(StandardSQLTypeName.TIMESTAMP.toString());

  private static final TableFieldSchema VALID =
      new TableFieldSchema().setName("valid")
        .setType(StandardSQLTypeName.BOOL.toString());

  private static final TableFieldSchema IDS =
      new TableFieldSchema().setName("ids")
        .setType(StandardSQLTypeName.INT64.toString())
        .setMode(Mode.REPEATED.toString());

  private static final Row FLAT_ROW =
      Row
        .withRowType(FLAT_TYPE)
        .addValues(123, 123.456, "test", new Date(123456), false)
        .build();

  private static final Row ARRAY_ROW =
      Row
        .withRowType(ARRAY_TYPE)
        .addValues((Object) Arrays.asList(123, 124))
        .build();

  private static final Row ROW_ROW =
      Row
        .withRowType(ROW_TYPE)
        .addValues(FLAT_ROW)
        .build();

  private static final Row ARRAY_ROW_ROW =
      Row
        .withRowType(ARRAY_ROW_TYPE)
        .addValues((Object) Arrays.asList(FLAT_ROW))
        .build();

  @Test public void testToTableSchema_flat() {
    TableSchema schema = toTableSchema(FLAT_TYPE);

    assertThat(schema.getFields(), containsInAnyOrder(ID, VALUE, NAME, TIMESTAMP, VALID));
  }

  @Test public void testToTableSchema_array() {
    TableSchema schema = toTableSchema(ARRAY_TYPE);

    assertThat(schema.getFields(), contains(IDS));
  }

  @Test public void testToTableSchema_row() {
    TableSchema schema = toTableSchema(ROW_TYPE);

    assertThat(schema.getFields().size(), equalTo(1));
    TableFieldSchema field = schema.getFields().get(0);
    assertThat(field.getFields(), containsInAnyOrder(ID, VALUE, NAME, TIMESTAMP, VALID));
    assertThat(field.getName(), equalTo("row"));
    assertThat(field.getType(), equalTo(StandardSQLTypeName.STRUCT.toString()));
    assertThat(field.getMode(), nullValue());
  }

  @Test public void testToTableSchema_array_row() {
    TableSchema schema = toTableSchema(ARRAY_ROW_TYPE);

    assertThat(schema.getFields().size(), equalTo(1));
    TableFieldSchema field = schema.getFields().get(0);
    assertThat(field.getFields(), containsInAnyOrder(ID, VALUE, NAME, TIMESTAMP, VALID));
    assertThat(field.getName(), equalTo("rows"));
    assertThat(field.getType(), equalTo(StandardSQLTypeName.STRUCT.toString()));
    assertThat(field.getMode(), equalTo(Mode.REPEATED.toString()));
  }

  @Test public void testToTableRow_flat() {
    TableRow row = toTableRow().apply(FLAT_ROW);

    assertThat(row.size(), equalTo(5));
    assertThat(row, hasEntry("id", 123));
    assertThat(row, hasEntry("value", 123.456));
    assertThat(row, hasEntry("name", "test"));
    assertThat(row, hasEntry("timestamp", new Date(123456)));
    assertThat(row, hasEntry("valid", false));
  }

  @Test public void testToTableRow_array() {
    TableRow row = toTableRow().apply(ARRAY_ROW);

    assertThat(row, hasEntry("ids", Arrays.asList(123, 124)));
    assertThat(row.size(), equalTo(1));
  }

  @Test public void testToTableRow_row() {
    TableRow row = toTableRow().apply(ROW_ROW);

    assertThat(row.size(), equalTo(1));
    row = (TableRow) row.get("row");
    assertThat(row.size(), equalTo(5));
    assertThat(row, hasEntry("id", 123));
    assertThat(row, hasEntry("value", 123.456));
    assertThat(row, hasEntry("name", "test"));
    assertThat(row, hasEntry("timestamp", new Date(123456)));
    assertThat(row, hasEntry("valid", false));
  }

  @Test public void testToTableRow_array_row() {
    TableRow row = toTableRow().apply(ARRAY_ROW_ROW);

    assertThat(row.size(), equalTo(1));
    row = ((List<TableRow>) row.get("rows")).get(0);
    assertThat(row.size(), equalTo(5));
    assertThat(row, hasEntry("id", 123));
    assertThat(row, hasEntry("value", 123.456));
    assertThat(row, hasEntry("name", "test"));
    assertThat(row, hasEntry("timestamp", new Date(123456)));
    assertThat(row, hasEntry("valid", false));
  }
}
