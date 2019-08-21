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
package org.apache.beam.sdk.coders;

import java.util.Arrays;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.io.BaseEncoding;

/**
 * A wrapper around a byte[] that uses structural, value-based equality rather than byte[]'s normal
 * object identity.
 */
public class StructuralByteArray {
  byte[] value;

  public StructuralByteArray(byte[] value) {
    this.value = value;
  }

  public byte[] getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof StructuralByteArray) {
      StructuralByteArray that = (StructuralByteArray) o;
      return Arrays.equals(this.value, that.value);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "base64:" + BaseEncoding.base64().encode(value);
  }
}
