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

package org.apache.beam.runners.core.construction;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.common.runner.v1.RunnerApi;
import org.apache.beam.sdk.common.runner.v1.RunnerApi.Components;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.WindowingStrategy;

/**
 * Vends Java SDK objects rehydrated from a Runner API {@link Components} collection.
 *
 * <p>This ensures maximum memoization of rehydrated components, which is semantically necessary for
 * {@link PCollection} and nice-to-have for other objects.
 */
public class RehydratedComponents {
  private final Components components;

  /**
   * A non-evicting cache, serving as a memo table for rehydrated {@link WindowingStrategy
   * WindowingStrategies}.
   */
  private final LoadingCache<String, WindowingStrategy<?, ?>> windowingStrategies =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<String, WindowingStrategy<?, ?>>() {
                @Override
                public WindowingStrategy<?, ?> load(String id) throws Exception {
                  return WindowingStrategyTranslation.fromProto(
                      components.getWindowingStrategiesOrThrow(id), RehydratedComponents.this);
                }
              });

  /** A non-evicting cache, serving as a memo table for rehydrated {@link Coder Coders}. */
  private final LoadingCache<String, Coder<?>> coders =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<String, Coder<?>>() {
                @Override
                public Coder<?> load(String id) throws Exception {
                  return CoderTranslation.fromProto(
                      components.getCodersOrThrow(id), RehydratedComponents.this);
                }
              });

  /**
   * A non-evicting cache, serving as a memo table for rehydrated {@link PCollection PCollections}.
   *
   * <p>Note that a {@link PCollection} always exists in the context of a {@link Pipeline}.
   */
  private final LoadingCache<Pipeline, LoadingCache<String, PCollection<?>>> pCollections =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Pipeline, LoadingCache<String, PCollection<?>>>() {
                @Override
                public LoadingCache<String, PCollection<?>> load(final Pipeline pipeline)
                    throws Exception {
                  return CacheBuilder.newBuilder()
                      .build(
                          new CacheLoader<String, PCollection<?>>() {
                            @Override
                            public PCollection<?> load(String id) throws Exception {
                              return PCollectionTranslation.fromProto(
                                  components.getPcollectionsOrThrow(id),
                                  pipeline,
                                  RehydratedComponents.this);
                            }
                          });
                }
              });


  /** Create a new {@link RehydratedComponents} from a Runner API {@link Components}. */
  public static RehydratedComponents create(RunnerApi.Components components) {
    return new RehydratedComponents(components);
  }

  private RehydratedComponents(RunnerApi.Components components) {
    this.components = components;
  }

  /**
   * Returns a {@link PCollection} rehydrated from the Runner API component with the given ID.
   *
   * <p>For a single instance of {@link RehydratedComponents}, this always returns the same instance
   * for a particular id.
   */
  public PCollection<?> getPCollection(String pCollectionId, Pipeline pipeline) throws IOException {
    try {
      return pCollections.get(pipeline).get(pCollectionId);
    } catch (ExecutionException exc) {
      throw new RuntimeException(exc);
    }
  }

  /**
   * Returns a {@link WindowingStrategy} rehydrated from the Runner API component with the given ID.
   *
   * <p>For a single instance of {@link RehydratedComponents}, this always returns the same instance
   * for a particular id.
   */
  public WindowingStrategy<?, ?> getWindowingStrategy(String windowingStrategyId)
      throws IOException {
    try {
      return windowingStrategies.get(windowingStrategyId);
    } catch (ExecutionException exc) {
      throw new RuntimeException(exc);
    }
  }

  /**
   * Returns a {@link Coder} rehydrated from the Runner API component with the given ID.
   *
   * <p>For a single instance of {@link RehydratedComponents}, this always returns the same instance
   * for a particular id.
   */
  public Coder<?> getCoder(String coderId) throws IOException {
    try {
      return coders.get(coderId);
    } catch (ExecutionException exc) {
      throw new RuntimeException(exc);
    }
  }
}
