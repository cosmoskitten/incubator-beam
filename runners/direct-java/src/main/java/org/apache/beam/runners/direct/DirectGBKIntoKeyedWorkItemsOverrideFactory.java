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
package org.apache.beam.runners.direct;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.beam.runners.core.GBKIntoKeyedWorkItems;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.util.KeyedWorkItem;
import org.apache.beam.sdk.util.KeyedWorkItemCoder;
import org.apache.beam.sdk.util.ReifyTimestampsAndWindows;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;

class DirectGBKIntoKeyedWorkItemsOverrideFactory implements PTransformOverrideFactory {
  @Override
  @SuppressWarnings("unchecked")
  public <InputT extends PInput, OutputT extends POutput> PTransform<InputT, OutputT> override(
      PTransform<InputT, OutputT> transform) {
    return new DirectGBKIntoKeyedWorkItems(transform.getName());
  }

  /** The Direct Runner specific implementation of {@link GBKIntoKeyedWorkItems}. */
  private static class DirectGBKIntoKeyedWorkItems<KeyT, InputT>
      extends PTransform<PCollection<KV<KeyT, InputT>>, PCollection<KeyedWorkItem<KeyT, InputT>>> {
    DirectGBKIntoKeyedWorkItems(String name) {
      super(name);
    }

    @Override
    public PCollection<KeyedWorkItem<KeyT, InputT>> apply(PCollection<KV<KeyT, InputT>> input) {
      checkArgument(input.getCoder() instanceof KvCoder);
      KvCoder<KeyT, InputT> kvCoder = (KvCoder<KeyT, InputT>) input.getCoder();
      return input
          .apply(new ReifyTimestampsAndWindows<KeyT, InputT>())
          .apply(new DirectGroupByKey.DirectGroupByKeyOnly<KeyT, InputT>())
          .setCoder(
              KeyedWorkItemCoder.of(
                  kvCoder.getKeyCoder(),
                  kvCoder.getValueCoder(),
                  input.getWindowingStrategy().getWindowFn().windowCoder()));
    }
  }
}
