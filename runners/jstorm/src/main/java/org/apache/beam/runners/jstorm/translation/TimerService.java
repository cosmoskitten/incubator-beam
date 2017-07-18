/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.jstorm.translation;

import java.io.Serializable;
import java.util.List;
import org.apache.beam.runners.core.TimerInternals;
import org.joda.time.Instant;

/**
 * Interface that tracks input watermarks and manages timers in each bolt.
 */
interface TimerService extends Serializable {

  void init(List<Integer> upStreamTasks);

  /**
   *
   * @param task
   * @param inputWatermark
   * @return new watermark if any timer is triggered during the update of watermark, otherwise 0
   */
  long updateInputWatermark(Integer task, long inputWatermark);

  long currentInputWatermark();

  long currentOutputWatermark();

  void clearWatermarkHold(String namespace);

  void addWatermarkHold(String namespace, Instant watermarkHold);

  void setTimer(Object key, TimerInternals.TimerData timerData, DoFnExecutor doFnExecutor);

  void fireTimers(long newWatermark);

  void deleteTimer(TimerInternals.TimerData timerData);
}
