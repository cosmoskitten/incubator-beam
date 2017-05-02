/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.jstorm.beam.translation.runtime;

import org.apache.beam.runners.core.TimerInternals;
import org.joda.time.Instant;

import java.io.Serializable;
import java.util.List;

/**
 * Interface that tracks input watermarks and manages timers in each bolt.
 */
public interface TimerService extends Serializable {

    void init(List<Integer> upStreamTasks);

    void updateInputWatermark(Integer task, long inputWatermark);

    long currentInputWatermark();

    long currentOutputWatermark();

    void clearWatermarkHold(String namespace);

    void addWatermarkHold(String namespace, Instant watermarkHold);

    void setTimer(Object key, TimerInternals.TimerData timerData, DoFnExecutor doFnExecutor);
}
