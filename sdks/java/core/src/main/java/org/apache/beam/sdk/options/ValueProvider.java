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
package org.apache.beam.sdk.options;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** {@link ValueProvider} is an interface which abstracts the notion of
 * fetching a value that may or may not be currently available.  This can be
 * used to parameterize transforms that only read values in at runtime, for
 * example.
 */
@JsonSerialize(using = ValueProvider.Serializer.class)
@JsonDeserialize(using = ValueProvider.Deserializer.class)
public interface ValueProvider<T> {

  static class Serializer extends JsonSerializer<ValueProvider<?>> {
    @Override
    public void serialize(ValueProvider<?> value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
      if (value.shouldValidate()) {
        jgen.writeStartObject();
        jgen.writeObject(value.get());
        jgen.writeEndObject();
      }
    }
  }

  static class Deserializer extends JsonDeserializer<ValueProvider<?>> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ValueProvider<?> deserialize(JsonParser jp, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
      checkNotNull(ctxt);
      JavaType type = checkNotNull(ctxt.getContextualType());
      JavaType[] params = type.findTypeParameters(ValueProvider.class);
      if (params.length != 1) {
        throw new RuntimeException("Unable to derive type for ValueProvider: " + type.toString());
      }
      JavaType innerType = params[0];
      JsonDeserializer dser = ctxt.findRootValueDeserializer(innerType);
      Object o = dser.deserialize(jp, ctxt);
      return StaticValueProvider.of(o);
    }
  }
  
  T get();

  /** Whether the contents of this ValueProvider is available to validation
   * routines that run at graph construction time.
   */
  boolean shouldValidate();

  /** {@link StaticValueProvider} is an implementation of ValueProvider that
   * allows for a static value to be provided.
   */
  public static class StaticValueProvider<T> implements ValueProvider<T>, Serializable {
    private final T value;

    StaticValueProvider(T value) {
      this.value = value;
    }

    public static <T> StaticValueProvider<T> of(T value) {
      StaticValueProvider<T> factory = new StaticValueProvider<>(value);
      return factory;
    }

    @Override
    public T get() {
      return value;
    }

    @Override
    public boolean shouldValidate() {
      return true;
    }
  }

  /** {@link RuntimeValueProvider} is an implementation of ValueProvider that
   * allows for a value to be provided at execution time rather than at graph
   * construction time.
   *
   * <p>To enforce this contract, if there is no default, users must only call
   * get() on the worker, which will provide the value of OPTIONS.
   */
  public static class RuntimeValueProvider<T> implements ValueProvider<T>, Serializable {
    private static PipelineOptions options = null;

    private final Class<? extends PipelineOptions> klass;
    private final String methodName;
    private final T defaultValue;

    RuntimeValueProvider(String methodName, Class<? extends PipelineOptions> klass) {
      this.methodName = methodName;
      this.klass = klass;
      this.defaultValue = null;
    }

    RuntimeValueProvider(String methodName, Class<? extends PipelineOptions> klass,
      T defaultValue) {
      this.methodName = methodName;
      this.klass = klass;
      this.defaultValue = defaultValue;
    }

    static void setRuntimeOptions(PipelineOptions runtimeOptions) {
      options = runtimeOptions;
    }

    @Override
    public T get() {
      if (options == null) {
        if (defaultValue != null) {
          return defaultValue;
        }
        throw new RuntimeException("Not called from a runtime context.");
      }
      try {
        Method method = klass.getMethod(methodName);
        PipelineOptions methodOptions = options.as(klass);
        InvocationHandler handler = Proxy.getInvocationHandler(methodOptions);
        return (T) handler.invoke(methodOptions, method, null);
      } catch (Throwable e) {
        throw new RuntimeException("Unable to load runtime value.", e);
      }
    }

    @Override
    public boolean shouldValidate() {
      return defaultValue != null;
    }
  }
}
