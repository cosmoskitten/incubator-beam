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
package org.apache.beam.sdk.io.clickhouse;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;

/** A descriptor for ClickHouse table schema. */
@Experimental(Experimental.Kind.SCHEMAS)
@AutoValue
public abstract class TableSchema implements Serializable {

  abstract List<Column> columns();

  public static TableSchema of(List<Column> columns) {
    return new AutoValue_TableSchema(columns);
  }

  /** A column in ClickHouse table. */
  @AutoValue
  public abstract static class Column implements Serializable {
    abstract String name();

    abstract ColumnType columnType();

    @Nullable
    abstract DefaultType defaultType();

    @Nullable
    public abstract Object defaultValue();

    public boolean materializedOrAlias() {
      return DefaultType.MATERIALIZED.equals(defaultType())
          || DefaultType.ALIAS.equals(defaultType());
    }

    public static Column of(String name, ColumnType columnType) {
      return of(name, columnType, null, null);
    }

    public static Column of(
        String name,
        ColumnType columnType,
        @Nullable DefaultType defaultType,
        @Nullable Object defaultValue) {
      return new AutoValue_TableSchema_Column(name, columnType, defaultType, defaultValue);
    }
  }

  /** An enumeration of possible types in ClickHouse. */
  public enum TypeName {
    // Primitive types
    DATE,
    DATETIME,
    FLOAT32,
    FLOAT64,
    INT8,
    INT16,
    INT32,
    INT64,
    STRING,
    UINT8,
    UINT16,
    UINT32,
    UINT64,
    // Composite types
    ARRAY
  }

  public enum DefaultType {
    DEFAULT,
    MATERIALIZED,
    ALIAS;

    public static Optional<DefaultType> parse(String str) {
      if ("".equals(str)) {
        return Optional.empty();
      } else {
        return Optional.of(valueOf(str));
      }
    }
  }

  /** A descriptor for a column type. */
  @AutoValue
  public abstract static class ColumnType implements Serializable {
    public static final ColumnType DATE = ColumnType.of(TypeName.DATE);
    public static final ColumnType DATETIME = ColumnType.of(TypeName.DATETIME);
    public static final ColumnType FLOAT32 = ColumnType.of(TypeName.FLOAT32);
    public static final ColumnType FLOAT64 = ColumnType.of(TypeName.FLOAT64);
    public static final ColumnType INT8 = ColumnType.of(TypeName.INT8);
    public static final ColumnType INT16 = ColumnType.of(TypeName.INT16);
    public static final ColumnType INT32 = ColumnType.of(TypeName.INT32);
    public static final ColumnType INT64 = ColumnType.of(TypeName.INT64);
    public static final ColumnType STRING = ColumnType.of(TypeName.STRING);
    public static final ColumnType UINT8 = ColumnType.of(TypeName.UINT8);
    public static final ColumnType UINT16 = ColumnType.of(TypeName.UINT16);
    public static final ColumnType UINT32 = ColumnType.of(TypeName.UINT32);
    public static final ColumnType UINT64 = ColumnType.of(TypeName.UINT64);

    // ClickHouse doesn't allow nested nullables, so boolean flag is enough
    abstract boolean nullable();

    public abstract TypeName typeName();

    @Nullable
    public abstract ColumnType arrayElementType();

    public static ColumnType of(TypeName typeName) {
      return ColumnType.builder().typeName(typeName).nullable(false).build();
    }

    public static ColumnType nullable(TypeName typeName) {
      return ColumnType.builder().typeName(typeName).nullable(true).build();
    }

    public static ColumnType array(ColumnType arrayElementType) {
      return ColumnType.builder()
          .typeName(TypeName.ARRAY)
          // ClickHouse doesn't allow nullable arrays
          .nullable(false)
          .arrayElementType(arrayElementType)
          .build();
    }

    public static ColumnType parse(String str) {
      try {
        return new org.apache.beam.sdk.io.clickhouse.impl.parser.ColumnTypeParser(
                new StringReader(str))
            .parse();
      } catch (org.apache.beam.sdk.io.clickhouse.impl.parser.ParseException e) {
        throw new IllegalArgumentException("failed to parse", e);
      }
    }

    public static Object parseDefaultExpression(ColumnType columnType, String str) {
      try {
        String value =
            new org.apache.beam.sdk.io.clickhouse.impl.parser.ColumnTypeParser(
                    new StringReader(str))
                .parseDefaultExpression();

        switch (columnType.typeName()) {
          case INT8:
            return Byte.valueOf(value);
          case INT16:
            return Short.valueOf(value);
          case INT32:
            return Integer.valueOf(value);
          case INT64:
            return Long.valueOf(value);
          case STRING:
            return value;
          case UINT8:
            return Short.valueOf(value);
          case UINT16:
            return Integer.valueOf(value);
          case UINT32:
            return Long.valueOf(value);
          case UINT64:
            return Long.valueOf(value);
          default:
            throw new UnsupportedOperationException("Unsupported type: " + columnType);
        }
      } catch (org.apache.beam.sdk.io.clickhouse.impl.parser.ParseException e) {
        throw new IllegalArgumentException("failed to parse", e);
      }
    }

    public static Builder builder() {
      return new AutoValue_TableSchema_ColumnType.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {

      public abstract Builder typeName(TypeName typeName);

      public abstract Builder arrayElementType(ColumnType arrayElementType);

      public abstract Builder nullable(boolean nullable);

      public abstract ColumnType build();
    }
  }
}
