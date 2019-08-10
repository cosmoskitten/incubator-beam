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

"""Internal side input transforms and implementations.

For internal use only; no backwards-compatibility guarantees.

Important: this module is an implementation detail and should not be used
directly by pipeline writers. Instead, users should use the helper methods
AsSingleton, AsIter, AsList and AsDict in apache_beam.pvalue.
"""

from __future__ import absolute_import

import typing
from builtins import object
from typing import Generic
from typing import Iterable
from typing import Iterator
from typing import TypeVar

from apache_beam.transforms import window

if typing.TYPE_CHECKING:
  from apache_beam.utils.windowed_value import WindowedValue
  from apache_beam.transforms.window import BoundedWindow

T = TypeVar('T')


# Top-level function so we can identify it later.
def _global_window_mapping_fn(w, global_window=window.GlobalWindow()):
  return global_window


def default_window_mapping_fn(target_window_fn):
  if target_window_fn == window.GlobalWindows():
    return _global_window_mapping_fn

  def map_via_end(source_window):
    return list(target_window_fn.assign(
        window.WindowFn.AssignContext(source_window.max_timestamp())))[-1]

  return map_via_end


class SideInputMap(object):
  """Represents a mapping of windows to side input values."""

  def __init__(self, view_class, view_options, iterable):
    self._window_mapping_fn = view_options.get(
        'window_mapping_fn', _global_window_mapping_fn)
    self._view_class = view_class
    self._view_options = view_options
    self._iterable = iterable
    self._cache = {}

  def __getitem__(self, window):
    if window not in self._cache:
      target_window = self._window_mapping_fn(window)
      self._cache[window] = self._view_class._from_runtime_iterable(
          _FilteringIterable(self._iterable, target_window), self._view_options)
    return self._cache[window]

  def is_globally_windowed(self):
    # type: () -> bool
    return self._window_mapping_fn == _global_window_mapping_fn


class _FilteringIterable(Generic[T]):
  """An iterable containing only those values in the given window.
  """

  def __init__(self, iterable, target_window):
    # type: (Iterable[WindowedValue[T]], BoundedWindow) -> None
    self._iterable = iterable
    self._target_window = target_window

  def __iter__(self):
    # type: () -> Iterator[T]
    for wv in self._iterable:
      if self._target_window in wv.windows:
        yield wv.value

  def __reduce__(self):
    # Pickle self as an already filtered list.
    return list, (list(self),)
