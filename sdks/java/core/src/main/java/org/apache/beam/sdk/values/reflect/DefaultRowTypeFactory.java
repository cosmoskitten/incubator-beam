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

package org.apache.beam.sdk.values.reflect;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.math.BigDecimal;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.coders.BigDecimalCoder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.FieldTypeDescriptor;

/**
 * A default implementation of the {@link RowTypeFactory} interface. The purpose of
 * the factory is to create a row types given a list of getters.
 *
 * <p>Row type is represented by {@link Schema} which essentially is a
 * {@code List<Pair<FieldName, Coder>>}.
 *
 * <p>Getters (e.g. pojo field getters) are represented by {@link FieldValueGetter} interface,
 * which exposes the field's name (see {@link FieldValueGetter#name()})
 * and java type (see {@link FieldValueGetter#type()}).
 *
 * <p>This is the default factory implementation used in {@link RowFactory}.
 *
 * <p>In other cases, when mapping requires extra logic, another implentation of the
 * {@link RowTypeFactory} should be used instead of this class.
 *
 */
public class DefaultRowTypeFactory implements RowTypeFactory {
  private static final Map<Class, FieldType> SUPPORTED_TYPES =
      ImmutableMap.<Class, FieldType>builder()
          .put(Boolean.class, FieldType.BOOLEAN)
          .put(Byte.class, FieldType.BYTE)
          .put(Character.class, FieldType.BYTE)
          .put(String.class, FieldType.STRING)
          .put(Short.class, FieldType.INT16)
          .put(Integer.class, FieldType.INT32)
          .put(Long.class, FieldType.INT64)
          .put(Float.class, FieldType.FLOAT)
          .put(Double.class, FieldType.DOUBLE)
          .put(BigDecimal.class, FieldType.DECIMAL)
          .put(GregorianCalendar.class, FieldType.TIME)
          .put(Date.class, FieldType.DATETIME)
          .build();

  // Does not support neested types.
  private FieldTypeDescriptor getTypeDescriptor(Class clazz) {
    FieldType fieldType = SUPPORTED_TYPES.get(clazz);
    if (fieldType == null) {
      throw new UnsupportedOperationException("Unsupported type");
    }
    return FieldTypeDescriptor.of(fieldType);
  }

  /**
   * Uses {@link FieldValueGetter#name()} as field names.
   * Uses {@link CoderRegistry#createDefault()} to get coders for {@link FieldValueGetter#type()}.
   */
  @Override
  public Schema createRowType(Iterable<FieldValueGetter> fieldValueGetters) {
    List<Schema.Field> fields = Lists.newArrayList();
    for (FieldValueGetter getter : fieldValueGetters) {
      fields.add(Schema.Field.of(getter.name(), getTypeDescriptor(getter.type())));
    }
    return Schema.of(fields);
  }

}
