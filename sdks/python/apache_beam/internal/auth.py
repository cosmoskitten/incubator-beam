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

"""Dataflow credentials and authentication."""

import datetime
import json
import logging
import os
import sys
import urllib2


from oauth2client.client import OAuth2Credentials

from apache_beam.utils import processes
from apache_beam.utils import retry
from apache_beam.utils.options import GoogleCloudOptions
from apache_beam.utils.options import PipelineOptions


# When we are running in GCE, we can authenticate with VM credentials.
is_running_in_gce = False

# When we are running in GCE, this value is set based on worker startup
# information.
executing_project = None


def set_running_in_gce(worker_executing_project):
  """Informs the authentication library that we are running in GCE.

  When we are running in GCE, we have the option of using the VM metadata
  credentials for authentication to Google services.

  Args:
    worker_executing_project: The project running the workflow. This information
      comes from worker startup information.
  """
  global is_running_in_gce
  global executing_project
  is_running_in_gce = True
  executing_project = worker_executing_project


class AuthenticationException(retry.PermanentException):
  pass


class GCEMetadataCredentials(OAuth2Credentials):
  """Credential object initialized using access token from GCE VM metadata."""

  def __init__(self, user_agent=None):
    """Create an instance of GCEMetadataCredentials.

    These credentials are generated by contacting the metadata server on a GCE
    VM instance.

    Args:
      user_agent: string, The HTTP User-Agent to provide for this application.
    """
    super(GCEMetadataCredentials, self).__init__(
        None,  # access_token
        None,  # client_id
        None,  # client_secret
        None,  # refresh_token
        datetime.datetime(2010, 1, 1),  # token_expiry, set to time in past.
        None,  # token_uri
        user_agent)

  @retry.with_exponential_backoff(
      num_retries=3, initial_delay_secs=1.0,
      retry_filter=retry.retry_on_server_errors_and_timeout_filter)
  def _refresh(self, http_request):
    refresh_time = datetime.datetime.now()
    req = urllib2.Request('http://metadata.google.internal/computeMetadata/v1/'
                          'instance/service-accounts/default/token',
                          headers={'Metadata-Flavor': 'Google'})
    token_data = json.loads(urllib2.urlopen(req).read())
    self.access_token = token_data['access_token']
    self.token_expiry = (refresh_time +
                         datetime.timedelta(seconds=token_data['expires_in']))


class _GCloudWrapperCredentials(OAuth2Credentials):
  """Credentials class wrapping gcloud credentials via shell."""

  def __init__(self, user_agent, **kwds):
    super(_GCloudWrapperCredentials, self).__init__(
        None, None, None, None, None, None, user_agent, **kwds)

  def _refresh(self, http_request):
    """Gets an access token using the gcloud client."""
    try:
      gcloud_process = processes.Popen(
          ['gcloud', 'auth', 'print-access-token'], stdout=processes.PIPE)
    except OSError as exn:
      logging.error('The gcloud tool was not found.', exc_info=True)
      raise AuthenticationException('The gcloud tool was not found: %s' % exn)
    output, _ = gcloud_process.communicate()
    self.access_token = output.strip()


def get_service_credentials():
  """Get credentials to access Google services."""
  user_agent = 'dataflow-python-sdk/1.0'
  if is_running_in_gce:
    # We are currently running as a GCE taskrunner worker.
    #
    # TODO(ccy): It's not entirely clear if these credentials are thread-safe.
    # If so, we can cache these credentials to save the overhead of creating
    # them again.
    return GCEMetadataCredentials(user_agent=user_agent)
  else:
    # We are currently being run from the command line.
    google_cloud_options = PipelineOptions(
        sys.argv).view_as(GoogleCloudOptions)
    if google_cloud_options.service_account_name:
      if not google_cloud_options.service_account_key_file:
        raise AuthenticationException(
            'key file not provided for service account.')
      if not os.path.exists(google_cloud_options.service_account_key_file):
        raise AuthenticationException(
            'Specified service account key file does not exist.')
      client_scopes = [
          'https://www.googleapis.com/auth/bigquery',
          'https://www.googleapis.com/auth/cloud-platform',
          'https://www.googleapis.com/auth/devstorage.full_control',
          'https://www.googleapis.com/auth/userinfo.email',
          'https://www.googleapis.com/auth/datastore'
      ]

      # The following code uses oauth2client >=2.0.0 functionality and if this
      # is not available due to import errors will use 1.5.2 functionality.
      try:
        from oauth2client.service_account import ServiceAccountCredentials
        return ServiceAccountCredentials.from_p12_keyfile(
            google_cloud_options.service_account_name,
            google_cloud_options.service_account_key_file,
            client_scopes,
            user_agent=user_agent)
      except ImportError:
        with file(google_cloud_options.service_account_key_file) as f:
          service_account_key = f.read()
        from oauth2client.client import SignedJwtAssertionCredentials
        return SignedJwtAssertionCredentials(
            google_cloud_options.service_account_name,
            service_account_key,
            client_scopes,
            user_agent=user_agent)

    else:
      return _GCloudWrapperCredentials(user_agent)
