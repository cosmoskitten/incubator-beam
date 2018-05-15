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
package org.apache.beam.sdk.extensions.euphoria.executor.local;

import java.util.Objects;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.windowing.Window;

/**
 * TODO: add javadoc.
 * @param <W>
 * @param <K>
 */
public final class KeyedWindow<W extends Window, K> {
  private final W window;
  private final K key;

  KeyedWindow(W window, K key) {
    this.window = Objects.requireNonNull(window);
    this.key = key;
  }

  public W window() {
    return window;
  }

  public K key() {
    return key;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof KeyedWindow) {
      KeyedWindow other = (KeyedWindow) o;
      return window.equals(other.window) && Objects.equals(key, other.key);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = window.hashCode();
    result = 31 * result + (key != null ? key.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "KeyedWindow{" + "window=" + window + ", key=" + key + '}';
  }
}
