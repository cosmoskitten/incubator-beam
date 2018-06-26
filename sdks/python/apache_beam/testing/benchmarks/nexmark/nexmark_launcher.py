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

"""Nexmark launcher.

The Nexmark suite is a series of queries (streaming pipelines) performed
on a simulation of auction events. The launcher orchestrates the generation
and parsing of streaming events and the running of queries.

Model
  - Person: Author of an auction or a bid.
  - Auction: Item under auction.
  - Bid: A bid for an item under auction.

Events
 - Create Person
 - Create Auction
 - Create Bid

Queries
  - Query0: Pass through (send and receive auction events).

Usage
  - DirectRunner
      python nexmark_launcher.py \
          --query/q <query number> \
          --project <project id> \
          --loglevel=DEBUG (optional) \

  - DataflowRunner
      python nexmark_launcher.py \
          --query/q <query number> \
          --project <project id> \
          --loglevel=DEBUG (optional) \
          --sdk_location <apache_beam tar.gz> \
          --staging_location=gs://... \
          --temp_location=gs://

"""

from __future__ import absolute_import
from __future__ import print_function

import argparse
import collections
import logging
import sys
import uuid

import apache_beam as beam
from apache_beam.options.pipeline_options import GoogleCloudOptions
from apache_beam.options.pipeline_options import PipelineOptions
from apache_beam.options.pipeline_options import SetupOptions
from apache_beam.options.pipeline_options import StandardOptions
from apache_beam.options.pipeline_options import TestOptions

from apache_beam.testing.benchmarks.nexmark.nexmark_util import Command
from apache_beam.testing.benchmarks.nexmark.queries import query0
from google.cloud import pubsub


class NexmarkLauncher(object):
  def __init__(self):
    self.parse_args()
    self.uuid = str(uuid.uuid4())
    self.topic_name = self.args.topic_name + self.uuid
    self.subscription_name = self.args.subscription_name + self.uuid

  def parse_args(self):
    parser = argparse.ArgumentParser()

    parser.add_argument('--query', '-q',
                        type=int,
                        action='append',
                        required=True,
                        choices=[0, 1, 2],
                        help='Query to run')

    parser.add_argument('--subscription_name',
                        type=str,
                        help='Pub/Sub subscription to read from')

    parser.add_argument('--topic_name',
                        type=str,
                        help='Pub/Sub topic to read from')

    parser.add_argument('--loglevel',
                        choices=['DEBUG', 'INFO', 'WARNING',
                                 'ERROR', 'CRITICAL'],
                        default='INFO',
                        help='Set logging level to debug')
    parser.add_argument('--input',
                        type=str,
                        required=True,
                        help='Path to the data file containing nexmark events.')

    self.args, self.pipeline_args = parser.parse_known_args()
    logging.basicConfig(level=getattr(logging, self.args.loglevel, None),
                        format='(%(threadName)-10s) %(message)s')

    self.pipeline_options = PipelineOptions(self.pipeline_args)
    logging.debug('args, pipeline_args: %s, %s', self.args, self.pipeline_args)

    # Usage with Dataflow requires a project to be supplied.
    self.project = self.pipeline_options.view_as(GoogleCloudOptions).project
    if self.project is None:
      parser.print_usage()
      print(sys.argv[0] + ': error: argument --project is required')
      sys.exit(1)

    # Pub/Sub is currently available for use only in streaming pipelines.
    self.streaming = self.pipeline_options.view_as(StandardOptions).streaming
    if self.streaming is None:
      parser.print_usage()
      print(sys.argv[0] + ': error: argument --streaming is required')
      sys.exit(1)

    # wait_until_finish ensures that the streaming job is canceled.
    self.wait_until_finish_duration = (
        self.pipeline_options.view_as(TestOptions).wait_until_finish_duration
    )
    if self.wait_until_finish_duration is None:
      parser.print_usage()
      print(sys.argv[0] + ': error: argument --wait_until_finish_duration is required') # pylint: disable=line-too-long
      sys.exit(1)

    # We use the save_main_session option because one or more DoFn's in this
    # workflow rely on global context (e.g., a module imported at module level).
    self.pipeline_options.view_as(SetupOptions).save_main_session = True

  def generate_events(self):
    publish_client = pubsub.Client(project=self.project)
    topic = publish_client.topic(self.topic_name)
    if topic.exists():
      topic.delete()
    topic.create()
    sub = topic.subscription(self.subscription_name)
    if sub.exists():
      sub.delete()
    sub.create()

    logging.info('Generating auction events to topic %s', topic.name)

    if self.args.input.startswith('gs://'):
      from apache_beam.io.gcp.gcsfilesystem import GCSFileSystem
      fs = GCSFileSystem(self.pipeline_options)
      with fs.open(self.args.input) as infile:
        for line in infile:
          topic.publish(line)
    else:
      with open(self.args.input) as infile:
        for line in infile:
          topic.publish(line)

    logging.info('Finished event generation.')

    # Read from PubSub into a PCollection.
    if self.args.subscription_name:
      raw_events = self.pipeline | 'ReadPubSub' >> beam.io.ReadFromPubSub(
          subscription=sub.full_name)
    else:
      raw_events = self.pipeline | 'ReadPubSub' >> beam.io.ReadFromPubSub(
          topic=topic.full_name)

    return raw_events

  def run_query(self, query, query_error):
    try:
      self.pipeline = beam.Pipeline(options=self.pipeline_options)
      raw_events = self.generate_events()
      query.load(raw_events)
      result = self.pipeline.run()
      job_duration = (
          self.pipeline_options.view_as(TestOptions).wait_until_finish_duration
      )
      if self.pipeline_options.view_as(StandardOptions).runner == 'DataflowRunner': # pylint: disable=line-too-long
        result.wait_until_finish(duration=job_duration)
        result.cancel()
      else:
        result.wait_until_finish()
    except Exception as exc:
      query_error.append(str(exc))
      raise

  def cleanup(self):
    publish_client = pubsub.Client(project=self.project)
    topic = publish_client.topic(self.topic_name)
    if topic.exists():
      topic.delete()
    sub = topic.subscription(self.subscription_name)
    if sub.exists():
      sub.delete()

  def run(self):
    queries = {
        0: query0,
        # TODO(mariagh): Add more queries.
    }

    for i in self.args.query:
      logging.info('Running query %d', i)

      # The DirectRunner is the default runner, and it needs
      # special handling to cancel streaming jobs.
      launch_from_direct_runner = self.pipeline_options.view_as(
          StandardOptions).runner in [None, 'DirectRunner']

      if launch_from_direct_runner:
        query_errors = collections.defaultdict(list)
        command = Command(self.run_query, args=[queries[i], query_errors[i]])
        query_duration = self.pipeline_options.view_as(TestOptions).wait_until_finish_duration # pylint: disable=line-too-long
        command.run(timeout=query_duration // 1000)
        if query_errors[i]:
          logging.error('Query failed with %s', ', '.join(query_errors[i]))
          exit(1)
      else:
        try:
          self.run_query(queries[i], query_errors=None)
        except Exception as exc:
          logging.error('Query failed with %s', str(exc))
          exit(1)
      logging.info('Queries run: %s', self.args.query)


if __name__ == '__main__':
  launcher = NexmarkLauncher()
  launcher.run()
  launcher.cleanup()
