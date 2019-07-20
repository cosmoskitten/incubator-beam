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
package org.apache.beam.sdk.extensions.zetasketch;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkNotNull;

import com.google.zetasketch.HyperLogLogPlusPlus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;

/** Coder for the {@link HyperLogLogPlusPlus} class with generic type parameter {@code T}. */
class HyperLogLogPlusPlusCoder<T> extends AtomicCoder<HyperLogLogPlusPlus<T>> {

  private static final HyperLogLogPlusPlusCoder<?> INSTANCE = new HyperLogLogPlusPlusCoder<>();

  private static final Coder<byte[]> BYTE_ARRAY_CODER = ByteArrayCoder.of();

  // Generic singleton factory pattern; the coder works for all HyperLogLogPlusPlus objects at
  // runtime regardless of type T
  @SuppressWarnings("unchecked")
  static <T> HyperLogLogPlusPlusCoder<T> of() {
    return (HyperLogLogPlusPlusCoder<T>) INSTANCE;
  }

  @Override
  public void encode(HyperLogLogPlusPlus<T> value, OutputStream outStream) throws IOException {
    checkNotNull(value, new CoderException("Cannot encode a null HyperLogLogPlusPlus value."));
    BYTE_ARRAY_CODER.encode(value.serializeToByteArray(), outStream);
  }

  @Override
  @SuppressWarnings("unchecked")
  public HyperLogLogPlusPlus<T> decode(InputStream inStream) throws IOException {
    // TODO: check if the forProto function can be made generic for type <T>
    // This will remove the type cast here and at 2 places in MergePartialFn
    return (HyperLogLogPlusPlus<T>) HyperLogLogPlusPlus.forProto(BYTE_ARRAY_CODER.decode(inStream));
  }

  @Override
  public void verifyDeterministic() throws NonDeterministicException {
    BYTE_ARRAY_CODER.verifyDeterministic();
  }

  // TODO: check if we can know the sketch size without serializing it
  // If so, isRegisterByteSizeObserverCheap() and registerByteSizeObserver() can be overridden
}
