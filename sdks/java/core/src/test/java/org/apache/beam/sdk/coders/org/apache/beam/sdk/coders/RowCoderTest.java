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
package org.apache.beam.sdk.coders.org.apache.beam.sdk.coders;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.Lists;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import org.apache.beam.sdk.coders.Coder.NonDeterministicException;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.testing.CoderProperties;
import org.apache.beam.sdk.values.Row;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

/** Unit tests for {@link RowCoder}. */
public class RowCoderTest {

  void checkEncodeDecode(Row row) throws IOException {
    RowCoder coder = RowCoder.of(row.getSchema());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    coder.encode(row, out);
    assertEquals(row, coder.decode(new ByteArrayInputStream(out.toByteArray())));
  }

  @Test
  public void testPrimitiveTypes() throws Exception {
    Schema schema =
        Schema.builder()
            .addByteField("f_byte")
            .addInt16Field("f_int16")
            .addInt32Field("f_int32")
            .addInt64Field("f_int64")
            .addDecimalField("f_decimal")
            .addFloatField("f_float")
            .addDoubleField("f_double")
            .addStringField("f_string")
            .addDateTimeField("f_datetime")
            .addBooleanField("f_boolean")
            .build();

    DateTime dateTime =
        new DateTime().withDate(1979, 03, 14).withTime(1, 2, 3, 4).withZone(DateTimeZone.UTC);
    Row row =
        Row.withSchema(schema)
            .addValues(
                (byte) 0, (short) 1, 2, 3L, new BigDecimal(2.3), 1.2f, 3.0d, "str", dateTime, false)
            .build();
    checkEncodeDecode(row);
  }

  @Test
  public void testNestedTypes() throws Exception {
    Schema nestedSchema = Schema.builder().addInt32Field("f1_int").addStringField("f1_str").build();
    Schema schema =
        Schema.builder().addInt32Field("f_int").addRowField("nested", nestedSchema).build();

    Row nestedRow = Row.withSchema(nestedSchema).addValues(18, "foobar").build();
    Row row = Row.withSchema(schema).addValues(42, nestedRow).build();
    checkEncodeDecode(row);
  }

  @Test
  public void testArrays() throws Exception {
    Schema schema = Schema.builder().addArrayField("f_array", FieldType.STRING).build();
    Row row = Row.withSchema(schema).addArray("one", "two", "three", "four").build();
    checkEncodeDecode(row);
  }

  @Test
  public void testArrayOfRow() throws Exception {
    Schema nestedSchema = Schema.builder().addInt32Field("f1_int").addStringField("f1_str").build();
    FieldType collectionElementType = FieldType.row(nestedSchema);
    Schema schema = Schema.builder().addArrayField("f_array", collectionElementType).build();
    Row row =
        Row.withSchema(schema)
            .addArray(
                Row.withSchema(nestedSchema).addValues(1, "one").build(),
                Row.withSchema(nestedSchema).addValues(2, "two").build(),
                Row.withSchema(nestedSchema).addValues(3, "three").build())
            .build();
    checkEncodeDecode(row);
  }

  @Test
  public void testArrayOfArray() throws Exception {
    FieldType arrayType = FieldType.array(FieldType.array(FieldType.INT32));
    Schema schema = Schema.builder().addField("f_array", arrayType).build();
    Row row =
        Row.withSchema(schema)
            .addArray(
                Lists.newArrayList(1, 2, 3, 4),
                Lists.newArrayList(5, 6, 7, 8),
                Lists.newArrayList(9, 10, 11, 12))
            .build();
    checkEncodeDecode(row);
  }

  @Test(expected = NonDeterministicException.class)
  public void testVerifyDeterministic() throws NonDeterministicException {
    Schema schema =
        Schema.builder()
            .addField("f1", FieldType.DOUBLE)
            .addField("f2", FieldType.FLOAT)
            .addField("f3", FieldType.INT32)
            .build();
    RowCoder coder = RowCoder.of(schema);

    coder.verifyDeterministic();
  }

  @Test(expected = NonDeterministicException.class)
  public void testVerifyDeterministicNestedRow() throws NonDeterministicException {
    Schema schema =
        Schema.builder()
            .addField(
                "f1",
                FieldType.row(
                    Schema.builder()
                        .addField("a1", FieldType.DOUBLE)
                        .addField("a2", FieldType.INT64)
                        .build()))
            .build();
    RowCoder coder = RowCoder.of(schema);

    coder.verifyDeterministic();
  }

  @Test
  public void testConsistentWithEqualsBytesField() throws Exception {
    Schema schema = Schema.of(Schema.Field.of("f1", FieldType.BYTES));
    Row row1 = Row.withSchema(schema).addValue(new byte[] {1, 2, 3, 4}).build();
    Row row2 = Row.withSchema(schema).addValue(new byte[] {1, 2, 3, 4}).build();
    RowCoder coder = RowCoder.of(schema);

    Assume.assumeTrue(coder.consistentWithEquals());

    CoderProperties.coderConsistentWithEquals(coder, row1, row2);
  }

  @Test
  public void testStructuralValueBytesField() throws Exception {
    Schema schema = Schema.of(Schema.Field.of("f1", FieldType.BYTES));
    Row row = Row.withSchema(schema).addValue(new byte[] {1, 2, 3, 4}).build();
    RowCoder coder = RowCoder.of(schema);

    CoderProperties.structuralValueDecodeEncodeEqual(coder, row);
  }

  @Test
  @Ignore // TODO should it even be possible to create rows with such schema?
  public void testConsistentWithEqualsMapWithBytesKeyField() throws Exception {
    FieldType fieldType = FieldType.map(FieldType.BYTES, FieldType.INT32);
    Schema schema = Schema.of(Schema.Field.of("f1", fieldType));
    RowCoder coder = RowCoder.of(schema);

    Map<byte[], Integer> map1 = Collections.singletonMap(new byte[] {1, 2, 3, 4}, 1);
    Row row1 = Row.withSchema(schema).addValue(map1).build();

    Map<byte[], Integer> map2 = Collections.singletonMap(new byte[] {1, 2, 3, 4}, 1);
    Row row2 = Row.withSchema(schema).addValue(map2).build();

    Assume.assumeTrue(coder.consistentWithEquals());

    CoderProperties.coderConsistentWithEquals(coder, row1, row2);
  }

  @Test
  public void testStructuralValueMapWithBytesKeyField() throws Exception {
    FieldType fieldType = FieldType.map(FieldType.BYTES, FieldType.INT32);
    Map<byte[], Integer> map = Collections.singletonMap(new byte[] {1, 2, 3, 4}, 1);

    Schema schema = Schema.of(Schema.Field.of("f1", fieldType));
    Row row = Row.withSchema(schema).addValue(map).build();
    RowCoder coder = RowCoder.of(schema);

    CoderProperties.structuralValueDecodeEncodeEqual(coder, row);
  }
}
