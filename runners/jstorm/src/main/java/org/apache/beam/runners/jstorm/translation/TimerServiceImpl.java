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

import static com.google.common.base.Preconditions.checkState;

import com.alibaba.jstorm.utils.Pair;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.joda.time.Instant;

/**
 * Default implementation of {@link TimerService}.
 */
class TimerServiceImpl implements TimerService {
  private ExecutorContext executorContext;
  private Map<Integer, DoFnExecutor> idToDoFnExecutor;

  private final ConcurrentMap<Integer, Long> upStreamTaskToInputWatermark =
      new ConcurrentHashMap<>();
  private final PriorityQueue<Long> inputWatermarks = new PriorityQueue<>();
  private final PriorityQueue<Instant> watermarkHolds = new PriorityQueue<>();
  private final Map<String, Instant> namespaceToWatermarkHold = new HashMap<>();
  private final PriorityQueue<TimerInternals.TimerData> eventTimeTimersQueue =
      new PriorityQueue<>();
  private final PriorityQueue<TimerInternals.TimerData> processTimeTimersQueue =
      new PriorityQueue<>();
  private final Map<TimerInternals.TimerData, Set<Pair<Integer, Object>>>
      timerDataToKeyedExecutors = Maps.newHashMap();

  private boolean initialized = false;

  public TimerServiceImpl() {
  }

  public TimerServiceImpl(ExecutorContext executorContext) {
    this.executorContext = executorContext;
    this.idToDoFnExecutor = executorContext.getExecutorsBolt().getIdToDoFnExecutor();
  }

  @Override
  public void init(List<Integer> upStreamTasks) {
    for (Integer task : upStreamTasks) {
      upStreamTaskToInputWatermark.put(task, BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis());
      inputWatermarks.add(BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis());
    }
    initialized = true;
  }

  @Override
  public synchronized long updateInputWatermark(Integer task, long taskInputWatermark) {
    checkState(initialized, "TimerService has not been initialized.");
    Long oldTaskInputWatermark = upStreamTaskToInputWatermark.get(task);
    // Make sure the input watermark don't go backward.
    if (taskInputWatermark > oldTaskInputWatermark) {
      upStreamTaskToInputWatermark.put(task, taskInputWatermark);
      inputWatermarks.add(taskInputWatermark);
      inputWatermarks.remove(oldTaskInputWatermark);

      long newLocalInputWatermark = currentInputWatermark();
      if (newLocalInputWatermark > oldTaskInputWatermark) {
        return newLocalInputWatermark;
      }
    }
    return 0;
  }

  @Override
  public long currentInputWatermark() {
    return initialized ? inputWatermarks.peek() : BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis();
  }

  @Override
  public long currentOutputWatermark() {
    if (watermarkHolds.isEmpty()) {
      return currentInputWatermark();
    } else {
      return Math.min(currentInputWatermark(), watermarkHolds.peek().getMillis());
    }
  }

  @Override
  public void clearWatermarkHold(String namespace) {
    Instant currentHold = namespaceToWatermarkHold.get(namespace);
    if (currentHold != null) {
      watermarkHolds.remove(currentHold);
      namespaceToWatermarkHold.remove(namespace);
    }
  }

  @Override
  public void addWatermarkHold(String namespace, Instant watermarkHold) {
    Instant currentHold = namespaceToWatermarkHold.get(namespace);
    if (currentHold == null) {
      namespaceToWatermarkHold.put(namespace, watermarkHold);
      watermarkHolds.add(watermarkHold);
    } else if (watermarkHold.isBefore(currentHold)) {
      namespaceToWatermarkHold.put(namespace, watermarkHold);
      watermarkHolds.add(watermarkHold);
      watermarkHolds.remove(currentHold);
    }
  }

  private PriorityQueue<TimerInternals.TimerData> getTimerQueue(TimeDomain timeDomain) {
    switch (timeDomain) {
      case EVENT_TIME :
        return eventTimeTimersQueue;
      case PROCESSING_TIME:
        return processTimeTimersQueue;
      default:
        throw new IllegalArgumentException(
            String.format("Does not support domain: %s.", timeDomain));
    }
  }

  @Override
  public void setTimer(Object key, TimerInternals.TimerData timerData, DoFnExecutor doFnExecutor) {
    Set<Pair<Integer, Object>> keyedExecutors = timerDataToKeyedExecutors.get(timerData);
    if (keyedExecutors == null) {
      keyedExecutors = Sets.newHashSet();
      getTimerQueue(timerData.getDomain()).add(timerData);
    }
    keyedExecutors.add(new Pair<>(doFnExecutor.getInternalDoFnExecutorId(), key));
    timerDataToKeyedExecutors.put(timerData, keyedExecutors);
  }

  @Override
  public void fireTimers(long currentTime, TimeDomain timeDomain) {
    TimerInternals.TimerData timerData;
    PriorityQueue<TimerInternals.TimerData> timerQueue = getTimerQueue(timeDomain);
    while ((timerData = timerQueue.peek()) != null
        && timerData.getTimestamp().getMillis() <= currentTime) {
      for (Pair<Integer, Object> keyedExecutor : timerDataToKeyedExecutors.get(timerData)) {
        DoFnExecutor executor = idToDoFnExecutor.get(keyedExecutor.getFirst());
        executor.onTimer(keyedExecutor.getSecond(), timerData);
      }
      timerQueue.remove();
      timerDataToKeyedExecutors.remove(timerData);
    }
  }

  @Override
  public void deleteTimer(TimerInternals.TimerData timerData) {
    getTimerQueue(timerData.getDomain()).remove(timerData);
    timerDataToKeyedExecutors.remove(timerData);
  }
}
