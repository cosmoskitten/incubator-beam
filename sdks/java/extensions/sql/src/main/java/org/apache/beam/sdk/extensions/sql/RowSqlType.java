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
package org.apache.beam.sdk.extensions.sql;

import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.values.Row;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;


/**
 * Type builder for {@link Row} with SQL types.
 *
 * <p>Limited SQL types are supported now, visit
 * <a href="https://beam.apache.org/documentation/dsls/sql/#data-types">data types</a>
 * for more details.
 *
 * <p>TODO: We should remove this class in favor of directly using Beam.Schema.Builder
 *
 */
@Experimental
public class RowSqlType {
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder class to construct {@link Schema}.
   */
  public static class Builder {
    Schema.Builder builder;

    public Builder withTinyIntField(String fieldName) {
      builder.addByteField(fieldName, true);
      return this;
    }

    public Builder withSmallIntField(String fieldName) {
      builder.addInt16Field(fieldName, true);
      return this;
    }

    public Builder withIntegerField(String fieldName) {
      builder.addInt32Field(fieldName, true);
      return this;
    }

    public Builder withBigIntField(String fieldName) {
      builder.addInt64Field(fieldName, true);
      return this;
    }

    public Builder withFloatField(String fieldName) {
      builder.addFloatField(fieldName, true);
      return this;
    }

    public Builder withDoubleField(String fieldName) {
      builder.addDoubleField(fieldName, true);
      return this;
    }

    public Builder withDecimalField(String fieldName) {
      builder.addDecimalField(fieldName, true);
      return this;
    }

    public Builder withBooleanField(String fieldName) {
      builder.addBooleanField(fieldName, true);
      return this;
    }

    public Builder withCharField(String fieldName) {
       builder.addField(Field.of(fieldName,
           CalciteUtils.toFieldType(SqlTypeName.CHAR)).withNullable(true));
       return this;
    }

    public Builder withVarcharField(String fieldName) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toFieldType(SqlTypeName.VARCHAR)).withNullable(true));
      return this;    }

    public Builder withTimeField(String fieldName) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toFieldType(SqlTypeName.TIME)).withNullable(true));
      return this;
    }

    public Builder withDateField(String fieldName) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toFieldType(SqlTypeName.DATE)).withNullable(true));
      return this;
    }

    public Builder withTimestampField(String fieldName) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toFieldType(SqlTypeName.TIMESTAMP)).withNullable(true));
      return this;
    }

    /**
     * Adds an ARRAY field with elements of the give type.
     */
    public Builder withArrayField(String fieldName, RelDataType relDataType) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toArrayType(relDataType)));
      return this;
    }

    /**
     * Adds an ARRAY field with elements of the give type.
     */
    public Builder withArrayField(String fieldName, SqlTypeName typeName) {
      builder.addField(Field.of(fieldName,
          CalciteUtils.toArrayType(typeName)));
      return this;
    }

    /**
     * Adds an ARRAY field with elements of {@code rowType}.
     */
    public Builder withArrayField(String fieldName, Schema schema) {
      FieldType componentType =
          FieldType
              .of(TypeName.ROW)
              .withRowSchema(schema);
      builder.addField(Field.of(fieldName,
          TypeName.ARRAY.type().withComponentType(componentType)));
      return this;
    }

    public Builder withRowField(String fieldName, Schema schema) {
       builder.addRowField(fieldName, schema, true);
       return this;
    }

    private Builder() {
      this.builder = Schema.builder();
    }

    public Schema build() {
      return builder.build();
    }
  }
}
