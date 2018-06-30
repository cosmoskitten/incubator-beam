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

package org.apache.beam.sdk.schemas;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SuppressWarnings("rawtypes")
@Experimental(Kind.SCHEMAS)
public @interface DefaultSchema {
  @CheckForNull
  Class<? extends SchemaProvider> value();

  static class DefaultSchemaProvider extends SchemaProvider {
    Map<TypeDescriptor, SchemaProvider> cachedProviders = Maps.newConcurrentMap();

    @Nullable
    private SchemaProvider getSchemaProvider(TypeDescriptor<?> typeDescriptor) {
      return cachedProviders.computeIfAbsent(
          typeDescriptor,
          type -> {
            Class<?> clazz = type.getRawType();
            DefaultSchema annotation = clazz.getAnnotation(DefaultSchema.class);
            if (annotation == null) {
              return null;
            }
            Class<? extends SchemaProvider> providerClass = annotation.value();
            checkArgument(
                providerClass != null,
                "Type "
                    + type
                    + " has a @DefaultSchemaProvider annotation "
                    + " with a null argument.");

            try {
              return providerClass.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException
                | InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
              throw new IllegalStateException(
                  "Failed to create SchemaProvider "
                      + providerClass.getSimpleName()
                      + " which was"
                      + " specified as the default SchemaProvider for type "
                      + type
                      + ". Make "
                      + " sure that this class has a default constructor.",
                  e);
            }
          });
    }

    @Override
    public <T> Schema schemaFor(TypeDescriptor<T> typeDescriptor) {
      SchemaProvider schemaProvider = getSchemaProvider(typeDescriptor);
      return (schemaProvider != null) ? schemaProvider.schemaFor(typeDescriptor) : null;
    }

    /**
     * Given a type, return a function that converts that type to a {@link Row} object If no schema
     * exists, returns null.
     */
    @Override
    public <T> SerializableFunction<T, Row> toRowFunction(TypeDescriptor<T> typeDescriptor) {
      SchemaProvider schemaProvider = getSchemaProvider(typeDescriptor);
      return (schemaProvider != null) ? schemaProvider.toRowFunction(typeDescriptor) : null;
    }

    /**
     * Given a type, returns a function that converts from a {@link Row} object to that type. If no
     * schema exists, returns null.
     */
    @Override
    public <T> SerializableFunction<Row, T> fromRowFunction(TypeDescriptor<T> typeDescriptor) {
      SchemaProvider schemaProvider = getSchemaProvider(typeDescriptor);
      return (schemaProvider != null) ? schemaProvider.fromRowFunction(typeDescriptor) : null;
    }
  }

  class DefaultSchemaProviderRegistrar implements SchemaProviderRegistrar {
    @Override
    public List<SchemaProvider> getSchemaProviders() {
      return ImmutableList.of(new DefaultSchemaProvider());
    }
  }
}
