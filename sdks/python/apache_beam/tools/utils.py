#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
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

"""Utility functions for all microbenchmarks."""

from __future__ import absolute_import
from __future__ import print_function

import collections
import gc
import os
import time

from numpy import median


def check_compiled(module):
  """Check whether given module has been compiled.
  Args:
    module: string, module name
  """
  check_module = __import__(module, globals(), locals(), -1)
  ext = os.path.splitext(check_module.__file__)[-1]
  if ext in ('.py', '.pyc'):
    raise RuntimeError(
        "Profiling uncompiled code.\n"
        "To compile beam, run "
        "'pip install Cython; python setup.py build_ext --inplace'")


def run_benchmarks(benchmark_suite, verbose=True):
  """Runs benchmarks, and collects execution times.

  A simple instrumentation to run a callable several times, collect and print
  its execution times.

  Args:
    benchmark_suite: A list of named tuples that describe benchmarks.
      Each tuple should have following key-value pairs:
        benchmark: a callable that takes an argument - a size of a benchmark,
          and returns a callable. A returned callable must run the code being
          benchmarked on an input of specified size.

          For example, one can implement a benchmark as:

          class MyBenchmark(object):
            def __init__(self, size):
              [do necessary initialization]

            def __call__(self):
              [run the code in question]

        size: int, a size of the input. Aggregated per-element metrics
           are counted based on the size of the input.
    num_runs: int, number of times to run each benchmark.
    verbose: bool, whether to print benchmark results to stdout.

  Returns:
    A dictionary of the form string -> list of floats. Keys of the dictionary
    are benchmark names, values are execution times in seconds for each run.
  """

  def get_name(benchmark_config):
    return getattr(benchmark_config.benchmark, '__name__',
                   str(benchmark_config.benchmark))

  def run(benchmark_fn, size):
    # Contain each run of a benchmark inside a function so that any temporary
    # objects can be garbage-collected after the run.
    benchmark_instance_callable = benchmark_fn(size)
    start = time.time()
    _ = benchmark_instance_callable()
    return time.time() - start

  cost_series = collections.defaultdict(list)
  for benchmark_config in benchmark_suite:
    name = get_name(benchmark_config)
    num_runs = benchmark_config.num_runs
    size = benchmark_config.size
    for run_id in range(num_runs):
      # Do a proactive GC before each run to minimize side-effects of different
      # runs.
      gc.collect()
      time_cost = run(benchmark_config.benchmark, size)
      cost_series[name].append(time_cost)
      if verbose:
        avg_cost = time_cost/size
        print("%s: run %d of %d, per element time cost: %g sec" % (
            name, run_id+1, num_runs, avg_cost))
    if verbose:
      print("")

  if verbose:
    print("Median time cost:")
    pad_length = max([len(get_name(bc)) for bc in benchmark_suite])

    for benchmark_config in benchmark_suite:
      name = get_name(benchmark_config)
      median_cost = median(cost_series[name])

      print("%s: per element median time cost: %g sec" % (
          name.ljust(pad_length, " "), median_cost/benchmark_config.size))

  return cost_series
