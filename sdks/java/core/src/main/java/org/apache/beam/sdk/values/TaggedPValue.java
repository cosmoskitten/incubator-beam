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
package org.apache.beam.sdk.values;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Iterables;
import org.apache.beam.sdk.annotations.Internal;

/**
 * <b><i>For internal use only; no backwards-compatibility guarantees.</i></b>
 *
 * <p>A (TupleTag, PValue) pair used in the expansion of a {@link PInput} or {@link POutput}.
 */
@AutoValue
@Internal
public abstract class TaggedPValue {
  public static TaggedPValue of(TupleTag<?> tag, PValue value) {
    return new AutoValue_TaggedPValue(tag, value);
  }

  public static TaggedPValue ofExpandedValue(PValue value) {
    return of(Iterables.getOnlyElement(value.expand().keySet()), value);
  }

  /** Returns the local tag associated with the {@link PValue}. */
  public abstract TupleTag<?> getTag();

  /** Returns the {@link PValue}. */
  public abstract PValue getValue();
}
