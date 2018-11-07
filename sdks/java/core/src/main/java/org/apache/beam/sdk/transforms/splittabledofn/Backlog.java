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
package org.apache.beam.sdk.transforms.splittabledofn;

import com.google.auto.value.AutoValue;
import java.util.Arrays;

/**
 * A representation for the amount of known work represented as a backlog.
 *
 * <p>It is up to each restriction tracker to convert between their natural representation of
 * outstanding work and this representation. For example:
 *
 * <ul>
 *   <li>Block based file source (e.g. Avro): From the end of the current block, the remaining
 *       number of bytes to the end of the restriction.
 *   <li>Pull based queue based source (e.g. Pubsub): The local/global backlog available in number
 *       of messages or number of {@code messages / bytes} that have not been processed.
 *   <li>Key range based source (e.g. Shuffle, Bigtable, ...): Scale the start key to be one and end
 *       key to be zero and interpolate the position of the next splittable key as the backlog. If
 *       information about the probability density function or cumulative distribution function is
 *       available, backlog interpolation can be improved. Alternatively, if the number of encoded
 *       bytes for the keys and values is known for the key range, the backlog of remaining bytes
 *       can be used.
 * </ul>
 *
 * <p>{@link RestrictionTracker}s should implement {@link Backlogs.HasBacklog} to report a backlog.
 *
 * <p>See <a href="https://s.apache.org/beam-bundles-backlog-splitting">Bundles w/ SplittableDoFns:
 * Backlog & Splitting</a> for further details.
 */
@AutoValue
public abstract class Backlog {
  /** Returns a backlog represented by the specified bytes. */
  public static Backlog of(byte[] bytes) {
    return new AutoValue_Backlog(false, Arrays.copyOf(bytes, bytes.length));
  }

  /** Returns an unknown backlog. */
  public static Backlog unknown() {
    return new AutoValue_Backlog(true, null);
  }

  /** Returns whether this backlog is known or unknown. */
  public abstract boolean isUnknown();

  /**
   * Returns the {@code byte[]} representation of the backlog if it is known.
   *
   * @throws IllegalStateException if the backlog is unknown.
   */
  public byte[] backlog() {
    if (isUnknown()) {
      throw new IllegalStateException("Backlog is unknown, there is no byte[] representation.");
    }
    return _backlog();
  }

  protected abstract byte[] _backlog();
}
