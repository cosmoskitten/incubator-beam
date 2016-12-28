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

package org.apache.beam.sdk.transforms;

import org.apache.beam.sdk.values.PCollection;

/**
 * A {@link PTransform PTransforms} for converting a {@link PCollection PCollection&lt;T&gt;} to a
 * {@link PCollection PCollection&lt;String&gt;}.
 */
public final class ToString {

  /**
   * Returns a {@code PTransform<PCollection<T>, PCollection<String>>} which transforms each
   * element of the input {@link PCollection} to a {@link String} using the
   * {@link Object#toString} method.
   */
  public static <T> PTransform<PCollection<T>, PCollection<String>> element() {
    return new Default<>();
  }

  private ToString() {
  }

  /**
   * A {@link PTransform} that converts a {@code PCollection<T>} to a {@code PCollection<String>}
   * using the {@link  Object#toString} method.
   */
  private static final class Default<T> extends PTransform<PCollection<T>, PCollection<String>> {
    @Override
    public PCollection<String> expand(PCollection<T> input) {
      return input.apply(MapElements.via(new ToStringFunction<T>()));
    }

    private static class ToStringFunction<T> extends SimpleFunction<T, String> {
      @Override
      public String apply(T input) {
        return input.toString();
      }
    }
  }
}
