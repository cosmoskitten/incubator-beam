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
package org.apache.beam.sdk.extensions.euphoria.core.executor;

import org.apache.beam.sdk.extensions.euphoria.core.annotation.audience.Audience;

/**
 * Thrown by executors at flow submission time when an invalid flow set up is detected, requiring
 * the user to explicitly provide a windowing strategy to a certain operator.
 */
@Audience(Audience.Type.EXECUTOR)
public class WindowingRequiredException extends IllegalStateException {
  public WindowingRequiredException(String message) {
    super(message);
  }

  public WindowingRequiredException(String message, Throwable cause) {
    super(message, cause);
  }
}
