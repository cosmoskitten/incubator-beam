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

"""DirectRunner, executing on the local machine.

The DirectRunner is a runner implementation that executes the entire
graph of transformations belonging to a pipeline on the local machine.
"""

# from __future__ import absolute_import

import cPickle as pickle
import json
import logging
import multiprocessing
import random
import socket
import struct
import subprocess
import sys
import threading
import time

# import collections
# import logging

# from google.protobuf import wrappers_pb2

import apache_beam as beam
# from apache_beam import typehints
# from apache_beam.metrics.execution import MetricsEnvironment
# from apache_beam.options.pipeline_options import DirectOptions
# from apache_beam.options.pipeline_options import StandardOptions
# from apache_beam.options.value_provider import RuntimeValueProvider
# from apache_beam.pvalue import PCollection
# from apache_beam.runners.direct.bundle_factory import BundleFactory
# from apache_beam.runners.runner import PipelineResult
from apache_beam.runners.runner import PipelineRunner
# from apache_beam.runners.runner import PipelineState
# from apache_beam.runners.runner import PValueCache
# from apache_beam.transforms.core import _GroupAlsoByWindow
# from apache_beam.transforms.core import _GroupByKeyOnly
# from apache_beam.transforms.ptransform import PTransform

# __all__ = ['LaserRunner']


from apache_beam.runners.laser.channels import ChannelConfig
from apache_beam.runners.laser.channels import LinkMode
from apache_beam.runners.laser.channels import LinkStrategy
from apache_beam.runners.laser.channels import LinkStrategyType
from apache_beam.runners.laser.channels import Interface
from apache_beam.runners.laser.channels import set_channel_config
from apache_beam.runners.laser.channels import get_channel_manager
from apache_beam.runners.laser.channels import remote_method


class LaserRunner(PipelineRunner):
  """Executes a pipeline using multiple processes on the local machine."""

  def __init__(self):
    pass 



class CoordinatorInterface(Interface):

  @remote_method(int, str)
  def register_worker(host_id, worker_id):
    raise NotImplementedError()

  @remote_method(str, returns=str)
  def ping(self, body):
    raise NotImplementedError()

class LaserCoordinator(threading.Thread, CoordinatorInterface):

  def __init__(self):
    self.channel_manager = get_channel_manager()
    self.worker_channels = {}
    super(LaserCoordinator, self).__init__()

  def run(self):
    print 'RUN'
    while True:
      for worker_id in self.worker_channels:
        start_time = time.time()
        for i in range(100):
          self.worker_channels[worker_id].ping()
        end_time = time.time()
        print 'WORKER PING', worker_id, (end_time - start_time) / 100
      time.sleep(1)


  def register_worker(self, host_id, worker_id):
    print 'REGISTERED', host_id, worker_id
    # self.worker_channels[worker_id] = self.channel_manager.get_interface(HostDescriptor(host_id), 'worker', WorkerInterface)


  def ping(self, body):
    # print 'LaserCoordinator PINGED', body
    return 'PINGED_%r' % body

class WorkerInterface(Interface):

  @remote_method(returns=str)
  def ping(self):
    raise NotImplementedError()


class LaserWorker(WorkerInterface):
  def __init__(self, options):
    self.options = options

  def ping(self):
    return 'OK'

  def run(self):
    worker_id = self.options['id']
    set_channel_config(ChannelConfig.from_dict(self.options['channel_config']))
    manager = get_channel_manager()
    manager.register_interface('%s/worker' % worker_id, self)

    coordinator = manager.get_interface('master/coordinator', CoordinatorInterface)
    while True:
      try:
        print 'START REGISTER'
        coordinator.register_worker(self.options['id'], worker_id)
        print 'REGISTER OK'
        break
      except Exception as e:
        print 'e', e
        time.sleep(2)

    while True:
      print 'RESUTLED!!!', coordinator.ping('wtf')
      time.sleep(2)
    sys.exit(1)


class ComputeNodeManagerInterface(Interface):
  pass

class ComputeNodeManager(ComputeNodeManagerInterface):
  def supports_scaling(self):
    return False

  def _check_started(self):
    if not self.started:
      raise Exception('Node manager not started.')

class ComputeNodeHandle(object):
  def __init__(self, manager, name, core_count):
    self.manager = manager
    self.name = name
    self.core_count = core_count

  def get_node_interface(self):
    pass


class ComputeNodeInterface(Interface):
  pass

class ComputeNode(ComputeNodeInterface):
  def __init__(self, node_config):
    # TODO: node_config should be a class or something, not just a dict
    #self.config = node_config
    # TODO: id?
    self.name = node_config['name']
    self.channel_manager = get_channel_manager()
    self.node_manager_interface = self.channel_manager.get_interface()


  def start(self):
    self.node_manager_interface.report_node_started(self.name)


class InProcessComputeNodeManager(ComputeNodeManager):

  def __init__(self, num_nodes=1):
    self.num_nodes = num_nodes
    self.started = False


  def start(self):
    for i in range(self.num_nodes):
      pass
    self.started = True


  def get_nodes(self):
    self._check_started()
    return []




def spawn_worker(options):
  p = subprocess.Popen(['python', '-m', 'apache_beam.runners.laser.laser_runner', '--worker', json.dumps(options)])




def run(argv):
  if '--worker' in argv:
    worker = LaserWorker(json.loads(argv[-1]))
    worker.run()
    sys.exit(0)

  COORDINATOR_PORT = random.randint(20000,30000)
  set_channel_config(ChannelConfig(
    node_addresses=['master'],
    link_strategies=[
      LinkStrategy(
        LinkStrategyType.LISTEN,
        mode=LinkMode.UNIX,
        address='./uds_socket3-%s' % COORDINATOR_PORT)]))
  manager = get_channel_manager()

  coordinator = LaserCoordinator()
  manager.register_interface('master/coordinator', coordinator)
  coordinator.start()

  print 'spawning worker'
  spawn_worker({
    'id': 'worker1',
    'channel_config': ChannelConfig(
    node_addresses=['worker1'],
    anycast_aliases = {'worker[any]': 'worker1'},
    link_strategies=[
      LinkStrategy(
        LinkStrategyType.CONNECT,
        mode=LinkMode.UNIX,
        address='./uds_socket3-%s' % COORDINATOR_PORT)]).to_dict(),
    })
  print 'DONE'
  # spawn_worker({
  #   'id': 102,
  #   'coordinator_host_id': manager.host_descriptor.host_id,
  #   'channel_config': {
  #     'host_id': 102,  # TODO: redundant
  #     'connect': True,
  #     'connect_mode': LinkMode.UNIX,
  #     'connect_address': './uds_socket3-%s' % COORDINATOR_PORT,
  #   }})
  # print 'DONE'
  time.sleep(10)

if __name__ == '__main__':
  logging.getLogger().setLevel(logging.DEBUG)
  run(sys.argv)

