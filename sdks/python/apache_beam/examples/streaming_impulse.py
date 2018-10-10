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

"""A streaming workflow that uses a synthetic streaming source.
"""

from __future__ import absolute_import

import argparse
import logging

from apache_beam.runners.portability.portable_runner import PortableRunner

from apache_beam.io.streaming_impulse_source import StreamingImpulseSource
from apache_beam.transforms.trigger import AfterProcessingTime, AccumulationMode, Repeatedly

import apache_beam as beam
import apache_beam.transforms.window as window
from apache_beam.options.pipeline_options import PipelineOptions

def split(s):
  a = s.split("-")
  return a[0], int(a[1])

def count(x):
  return x[0], sum(x[1])

def apply_timestamp(element):
  import time
  import apache_beam.transforms.window as window
  yield window.TimestampedValue(element, time.time())

def run(argv=None):
  """Build and run the pipeline."""
  args = ["--runner=PortableRunner",
          "--job_endpoint=localhost:8099",
          "--streaming"]
  if argv:
    args.extend(argv)

  parser = argparse.ArgumentParser()
  known_args, pipeline_args = parser.parse_known_args(args)

  pipeline_options = PipelineOptions(pipeline_args)

  p = beam.Pipeline(options=pipeline_options)

  messages = (p | StreamingImpulseSource())

  (messages | 'decode' >> beam.Map(lambda x: ('', 1))
   | 'window' >> beam.WindowInto(window.GlobalWindows(),
                                 trigger=Repeatedly(AfterProcessingTime(5 * 1000)),
                                 accumulation_mode=AccumulationMode.DISCARDING)
   | 'group' >> beam.GroupByKey()
   | 'count' >> beam.Map(count)
   | 'log' >> beam.Map(lambda x: logging.info("%d" % x[1])))

  result = p.run()
  result.wait_until_finish()


if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  run()
