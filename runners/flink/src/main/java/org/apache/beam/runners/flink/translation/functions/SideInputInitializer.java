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
package org.apache.beam.runners.flink.translation.functions;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Objects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.Materializations;
import org.apache.beam.sdk.transforms.Materializations.MultimapView;
import org.apache.beam.sdk.transforms.ViewFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.flink.api.common.functions.BroadcastVariableInitializer;

/**
 * {@link BroadcastVariableInitializer} that initializes the broadcast input as a {@code Map}
 * from window to side input.
 */
public class SideInputInitializer<ViewT>
    implements BroadcastVariableInitializer<WindowedValue<?>, Map<BoundedWindow, ViewT>> {

  PCollectionView<ViewT> view;

  public SideInputInitializer(PCollectionView<ViewT> view) {
    checkArgument(
        Materializations.MULTIMAP_MATERIALIZATION_URN.equals(
            view.getViewFn().getMaterialization().getUrn()),
        "This handler is only capable of dealing with %s materializations "
            + "but was asked to handle %s for PCollectionView with tag %s.",
        Materializations.MULTIMAP_MATERIALIZATION_URN,
        view.getViewFn().getMaterialization().getUrn(),
        view.getTagInternal().getId());
    this.view = view;
  }

  @Override
  public Map<BoundedWindow, ViewT> initializeBroadcastVariable(
      Iterable<WindowedValue<?>> inputValues) {

    // first partition into windows
    Map<BoundedWindow, List<WindowedValue<KV<?, ?>>>> partitionedElements = new HashMap<>();
    for (WindowedValue<KV<?, ?>> value
        : (Iterable<WindowedValue<KV<?, ?>>>) (Iterable) inputValues) {
      for (BoundedWindow window: value.getWindows()) {
        List<WindowedValue<KV<?, ?>>> windowedValues = partitionedElements.get(window);
        if (windowedValues == null) {
          windowedValues = new ArrayList<>();
          partitionedElements.put(window, windowedValues);
        }
        windowedValues.add(value);
      }
    }

    Map<BoundedWindow, ViewT> resultMap = new HashMap<>();

    for (Map.Entry<BoundedWindow, List<WindowedValue<KV<?, ?>>>> elements:
        partitionedElements.entrySet()) {

      ViewFn<MultimapView, ViewT> viewFn = (ViewFn<MultimapView, ViewT>) view.getViewFn();
      Coder keyCoder = ((KvCoder<?, ?>) view.getCoderInternal()).getKeyCoder();
      // We specifically use an array list multimap to allow for:
      //  * null keys
      //  * null values
      //  * duplicate values
      Multimap<Object, Object> multimap = ArrayListMultimap.create();
      for (WindowedValue<KV<?, ?>> element : elements.getValue()) {
        multimap.put(
            keyCoder.structuralValue(element.getValue().getKey()),
            element.getValue().getValue());
      }

      resultMap.put(elements.getKey(), viewFn.apply(new MultimapBasedPrimitiveMultimapView(
          keyCoder, Multimaps.unmodifiableMultimap(multimap))));
    }

    return resultMap;
  }

  class MultimapBasedPrimitiveMultimapView<K, V>
      implements MultimapView<K, V> {
    private final Coder<K> keyCoder;
    private final Multimap<Object, V> structuredKeyToValuesMap;

    private MultimapBasedPrimitiveMultimapView(Coder<K> keyCoder, Multimap<Object, V> data) {
      this.keyCoder = keyCoder;
      this.structuredKeyToValuesMap = data;
    }

    @Override
    public Iterable<V> get(K input) {
      return Objects.firstNonNull(structuredKeyToValuesMap.get(keyCoder.structuralValue(input)),
          Collections.EMPTY_LIST);
    }
  }
}
