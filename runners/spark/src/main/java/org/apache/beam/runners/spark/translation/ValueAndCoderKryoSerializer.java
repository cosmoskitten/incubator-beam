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
package org.apache.beam.runners.spark.translation;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.io.IOException;

public class ValueAndCoderKryoSerializer<T> extends Serializer<ValueAndCoderLazySerializable<T>> {

  @Override
  public void write(Kryo kryo, Output output, ValueAndCoderLazySerializable<T> item) {
    try {
      item.writeCommon(output);
    } catch (IOException e) {
      throw new KryoException(e);
    }
  }

  @Override
  public ValueAndCoderLazySerializable<T> read(
      Kryo kryo, Input input, Class<ValueAndCoderLazySerializable<T>> type) {
    ValueAndCoderLazySerializable<T> value = new ValueAndCoderLazySerializable<>();
    try {
      value.readCommon(input);
    } catch (IOException e) {
      throw new KryoException(e);
    }
    return value;
  }
}
