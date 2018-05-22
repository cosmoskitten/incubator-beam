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
"""A :class:`FileHandler` to work with :class:`ArtifactStagingServiceStub`.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import os

from apache_beam.portability.api import beam_artifact_api_pb2
from apache_beam.portability.api import beam_artifact_api_pb2_grpc
from apache_beam.runners.portability.stager import Stager


class PortableStager(Stager):
  """An implementation of :class:`Stager` to stage files on
  ArtifactStagingService.

  The class keeps track of pushed files and commit manifest once all files are
  uploaded.

  Note: This class is not thread safe and user of this class should ensure
  thread safety.
  """

  def __init__(self, artifact_service_channel):
    """Creates a new Stager to stage file to ArtifactStagingService.

    Args:
      artifact_service_channel: Channel used to interact with
        ArtifactStagingService.User owns the channel and should close it when
        finished.
    """
    super(PortableStager, self).__init__()
    self._artifact_staging_stub = beam_artifact_api_pb2_grpc.\
        ArtifactStagingServiceStub(channel=artifact_service_channel)
    self._artifacts = []

  def stage_artifact(self, local_path_to_artifact, artifact_name):
    """Stage a file to ArtifactStagingService.

    Args:
      local_path_to_artifact: Path of file to be uploaded.
      artifact_name: File name on the artifact server.
    """
    if not os.path.isfile(local_path_to_artifact):
      raise ValueError(
          'Cannot stage {0} to artifact server. Only local files can be staged.'
          .format(local_path_to_artifact))

    def artifact_request_generator():
      metadata = beam_artifact_api_pb2.ArtifactMetadata(name=artifact_name)
      request = beam_artifact_api_pb2.PutArtifactRequest(metadata=metadata)
      yield request
      with open(local_path_to_artifact, 'rb') as f:
        while True:
          chunk = f.read(1 << 21)  # 2MB
          if not chunk:
            break
          request = beam_artifact_api_pb2.PutArtifactRequest(
              data=beam_artifact_api_pb2.ArtifactChunk(data=chunk))
          yield request
      self._artifacts.append(metadata)

    self._artifact_staging_stub.PutArtifact(artifact_request_generator())

  def commit_manifest(self):
    manifest = beam_artifact_api_pb2.Manifest(artifact=self._artifacts)
    self._artifacts = []
    self._artifact_staging_stub.CommitManifest(
        beam_artifact_api_pb2.CommitManifestRequest(manifest=manifest))
