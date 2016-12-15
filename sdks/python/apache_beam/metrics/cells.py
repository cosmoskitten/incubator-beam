#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
This file contains metric cell classes. A Metric cell is used to accumulate
in-memory changes to a metric. It represents a specific metric in a single
context.

Cells depend on a 'dirty-bit' in the CellCommitState class that tracks whether
a cell's updates have been committed.
"""

import threading

from apache_beam.metrics.metricbase import Counter
from apache_beam.metrics.metricbase import Distribution


class CellCommitState(object):
  """Atomically tracks a cell's dirty/clean commit status.

  Reporting a metric udate works in a two-step process: First updates to the
  metric are received, and the metric is marked as 'dirty'. Later updates are
  committed, and then the cell may be marked as 'clean'.

  The tracking of a cell's state is done conservatively: A metric may be
  reported DIRTY even if updates have not occurred.

  This class is thread-safe.
  """

  # Indicates that there have been changes to the cell since the last commit.
  DIRTY = 0
  # Indicates that there have NOT been changes to the cell since last commit.
  CLEAN = 1
  # Indicates that a commit of the current value is in progress.
  COMMITTING = 2

  def __init__(self):
    """Initializes ``CellCommitState``.

    A cell is initialized as dirty
    """
    self._lock = threading.Lock()
    self._state = CellCommitState.DIRTY

  @property
  def state(self):
    with self._lock:
      return self._state

  def after_modification(self):
    """Indicate that changes have been made to the metric being tracked.

    Should be called after modification of the metric value.
    """
    with self._lock:
      self._state = CellCommitState.DIRTY

  def after_commit(self):
    """Mark changes made up to the last call to ``before_commit`` as committed.

    The next call to ``before_commit`` will return ``false`` unless there have
    been changes made.
    """
    with self._lock:
      if self._state == CellCommitState.COMMITTING:
        self._state = CellCommitState.CLEAN

  def before_commit(self):
    """Check the dirty state, and mark the metric as committing.

    After this call, the state is either CLEAN, or COMMITTING. If the state
    was already CLEAN, then we simply return. If it was either DIRTY or
    COMMITTING, then we set the cell as COMMITTING (e.g. in the middle of
    a commit).
    After a commit is successful, ``after_commit`` should be called.
    """
    with self._lock:
      if self._state == CellCommitState.CLEAN:
        return False
      else:
        self._state = CellCommitState.COMMITTING
        return True


class MetricCell(object):
  """Accumulates in-memory changes to a metric.

  A MetricCell represents a specific metric in a single context and bundle.
  All subclasses must be thread safe, as these are used in the pipeline runners,
  and may be subject to parallel/concurrent updates. Cells should only be used
  directly within a runner.
  """
  def __init__(self):
    self.commit = CellCommitState()
    self._lock = threading.Lock()

  def get_cumulative(self):
    raise NotImplementedError


class CounterCell(Counter, MetricCell):
  """Tracks the current value and delta of a counter metric.

  Each cell tracks the state of a metric independently per context per bundle.
  Therefore, each metric has a different cell in each bundle, cells are
  aggregated by the runner.

  This class is thread safe.
  """
  def __init__(self, *args):
    super(CounterCell, self).__init__(*args)
    self.value = 0

  def combine(self, other):
    result = CounterCell()
    result.inc(self.value + other.value)
    return result

  def inc(self, n=1):
    with self._lock:
      self.value += n
      self.commit.after_modification()

  def get_cumulative(self):
    with self._lock:
      return self.value


class DistributionCell(Distribution, MetricCell):
  """Tracks the current value and delta for a distribution metric.

  Each cell tracks the state of a metric independently per context per bundle.
  Therefore, each metric has a different cell in each bundle, that is later
  aggregated.

  This class is thread safe.
  """
  def __init__(self, *args):
    super(DistributionCell, self).__init__(*args)
    self.data = DistributionData(0, 0, None, None)

  def combine(self, other):
    result = DistributionCell()
    result.data = self.data.combine(other.data)
    return result

  def update(self, value):
    with self._lock:
      self.commit.after_modification()
      self._update(value)

  def _update(self, value):
    value = int(value)
    self.data._count += 1
    self.data._sum += value
    self.data._min = (value
                      if self.data.min is None or self.data.min > value
                      else self.data.min)
    self.data._max = (value
                      if self.data.max is None or self.data.max < value
                      else self.data.max)

  def get_cumulative(self):
    with self._lock:
      return self.data.get_cumulative()


class DistributionData(object):
  """The data structure that holds data about a distribution metric.

  This object is not thread safe, so it's not supposed to be modified
  by other than the DistributionCell that contains it.
  """
  def __init__(self, sum, count, min, max):
    self._sum = sum
    self._count = count
    self._min = min
    self._max = max

  def __eq__(self, other):
    return (self.sum == other.sum and
            self.count == other.count and
            self.min == other.min and
            self.max == other.max)

  def __neq__(self, other):
    return not self.__eq__(other)

  def __repr__(self):
    return '<DistributionData({}, {}, {}, {})>'.format(self.sum,
                                                       self.count,
                                                       self.min,
                                                       self.max)

  def get_cumulative(self):
    return DistributionData(self.sum, self.count, self.min, self.max)

  @property
  def sum(self):
    return self._sum

  @property
  def count(self):
    return self._count

  @property
  def min(self):
    return self._min

  @property
  def max(self):
    return self._max

  @property
  def mean(self):
    """Returns the float mean of the distribution."""
    return float(self.sum) / self.count

  def combine(self, other):
    if other is None:
      return self
    else:
      new_min = (None if self.min is None and other.min is None else
                 min(x for x in (self.min, other.min) if x is not None))
      new_max = (None if self.max is None and other.max is None else
                 max(x for x in (self.max, other.max) if x is not None))
      return DistributionData(
          self.sum + other.sum,
          self.count + other.count,
          new_min,
          new_max)

  @classmethod
  def singleton(cls, value):
    return DistributionData(value, 1, value, value)


class MetricAggregator(object):
  """Base interface for aggregating metric data during pipeline execution."""
  def combine(self, updates):
    raise NotImplementedError

  def zero(self):
    raise NotImplementedError


class CounterAggregator(MetricAggregator):
  """Aggregator for Counter metric data during pipeline execution.

  Values aggregated should be ``int`` objects.
  """
  def zero(self):
    return 0

  def combine(self, x, y):
    return int(x) + int(y)


class DistributionAggregator(MetricAggregator):
  """Aggregator for Distribution metric data during pipeline execution.

  Values aggregated should be ``DistributionData`` objects.
  """
  def zero(self):
    return DistributionData(0, 0, None, None)

  def combine(self, x, y):
    return x.combine(y)
