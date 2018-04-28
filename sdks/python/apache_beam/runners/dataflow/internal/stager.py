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
"""Support for installing custom code and required dependencies.

Workflows, with the exception of very simple ones, are organized in multiple
modules and packages. Typically, these modules and packages have
dependencies on other standard libraries. Beam relies on the Python
setuptools package to handle these scenarios. For further details please read:
https://pythonhosted.org/an_example_pypi_project/setuptools.html

When a runner tries to run a pipeline it will check for a --requirements_file
and a --setup_file option.

If --setup_file is present then it is assumed that the folder containing the
file specified by the option has the typical layout required by setuptools and
it will run 'python setup.py sdist' to produce a source distribution. The
resulting tarball (a .tar or .tar.gz file) will be staged at the staging
location specified as job option. When a worker starts it will check for the
presence of this file and will run 'easy_install tarball' to install the
package in the worker.

If --requirements_file is present then the file specified by the option will be
staged in the staging location.  When a worker starts it will check for the
presence of this file and will run 'pip install -r requirements.txt'. A
requirements file can be easily generated by running 'pip freeze -r
requirements.txt'. The reason a runner does not run this automatically is
because quite often only a small fraction of the dependencies present in a
requirements.txt file are actually needed for remote execution and therefore a
one-time manual trimming is desirable.

TODO(silviuc): Staged files should have a job specific prefix.
To prevent several jobs in the same project stomping on each other due to a
shared staging location.

TODO(silviuc): Should we allow several setup packages?
TODO(silviuc): We should allow customizing the exact command for setup build.
"""

import functools
import glob
import logging
import os
import shutil
import subprocess
import sys
import tempfile

import pkg_resources

from apache_beam.internal import pickler
from apache_beam.io.filesystems import FileSystems
from apache_beam.options.pipeline_options import SetupOptions
from apache_beam.runners.dataflow.internal import names
from apache_beam.utils import processes

# All constants are for internal use only; no backwards-compatibility
# guarantees.

# Standard file names used for staging files.
WORKFLOW_TARBALL_FILE = 'workflow.tar.gz'
REQUIREMENTS_FILE = 'requirements.txt'
EXTRA_PACKAGES_FILE = 'extra_packages.txt'

# Package names for distributions
BEAM_PACKAGE_NAME = 'apache-beam'


class FileHandler(object):

  def file_copy(self, from_path, to_path):
    """Copies a local file to a remote location or vice versa."""
    logging.info('File copy from %s to %s.', from_path, to_path)

    if not os.path.isdir(os.path.dirname(to_path)):
      logging.info(
          'Created folder (since we have not done yet, and any errors '
          'will follow): %s ', os.path.dirname(to_path))
      os.mkdir(os.path.dirname(to_path))
    shutil.copyfile(from_path, to_path)

  def file_download(self, from_url, to_path):
    """Downloads a file over http/https from a or copy them from a remote
    location."""
    if from_url.startswith('http://') or from_url.startswith('https://'):
      # TODO(silviuc): We should cache downloads so we do not do it for every job.
      try:
        # We check if the file is actually there because wget returns a file
        # even for a 404 response (file will contain the contents of the 404
        # response).
        # TODO(angoenka): Extract and use the filename when downloading file.
        response, content = __import__('httplib2').Http().request(from_url)
        if int(response['status']) >= 400:
          raise RuntimeError(
              'Beam SDK not found at %s (response: %s)' % (from_url, response))
        with open(to_path, 'w') as f:
          f.write(content)
      except Exception:
        logging.info('Failed to download Beam SDK from %s', from_url)
        raise
    else:
      # Copy the file from the remote file system to loca files system.
      self.file_copy(from_url, to_path)

  def is_remote_path(self, path):
    return path.find('://') is not -1


class Stager(object):

  def __init__(self, file_handler=FileHandler()):
    self.file_handler = file_handler

  def _stage_extra_packages(self, extra_packages, staging_location, temp_dir):
    """Stages a list of local extra packages.

      Args:
        extra_packages: Ordered list of local paths to extra packages to be
          staged.
        staging_location: Staging location for the packages.
        temp_dir: Temporary folder where the resource building can happen.
          Caller is responsible for cleaning up this folder after this function
          returns.

      Returns:
        A list of file names (no paths) for the resources staged. All the files
        are assumed to be staged in staging_location.

      Raises:
        RuntimeError: If files specified are not found or do not have expected
          name patterns.
      """
    resources = []
    staging_temp_dir = None
    local_packages = []
    for package in extra_packages:
      if not (os.path.basename(package).endswith('.tar') or
              os.path.basename(package).endswith('.tar.gz') or
              os.path.basename(package).endswith('.whl') or
              os.path.basename(package).endswith('.zip')):
        raise RuntimeError(
            'The --extra_package option expects a full path ending with '
            '".tar", ".tar.gz", ".whl" or ".zip" instead of %s' % package)
      if os.path.basename(package).endswith('.whl'):
        logging.warning(
            'The .whl package "%s" is provided in --extra_package. '
            'This functionality is not officially supported. Since wheel '
            'packages are binary distributions, this package must be '
            'binary-compatible with the worker environment (e.g. Python 2.7 '
            'running on an x64 Linux host).')

      if not os.path.isfile(package):
        if self.file_handler.is_remote_path(package):
          # Download remote package.
          if not staging_temp_dir:
            staging_temp_dir = tempfile.mkdtemp(dir=temp_dir)
          logging.info('Downloading extra package: %s locally before staging',
                       package)
          if os.path.isfile(staging_temp_dir):
            local_file_path = staging_temp_dir
          else:
            _, last_component = FileSystems.split(package)
            local_file_path = FileSystems.join(staging_temp_dir, last_component)
          self.file_handler.file_download(package, local_file_path)
        else:
          raise RuntimeError(
              'The file %s cannot be found. It was specified in the '
              '--extra_packages command line option.' % package)
      else:
        local_packages.append(package)

    if staging_temp_dir:
      local_packages.extend([
          FileSystems.join(staging_temp_dir, f)
          for f in os.listdir(staging_temp_dir)
      ])

    for package in local_packages:
      basename = os.path.basename(package)
      staged_path = FileSystems.join(staging_location, basename)
      self.file_handler.file_copy(package, staged_path)
      resources.append(basename)
    # Create a file containing the list of extra packages and stage it.
    # The file is important so that in the worker the packages are installed
    # exactly in the order specified. This approach will avoid extra PyPI
    # requests. For example if package A depends on package B and package A
    # is installed first then the installer will try to satisfy the
    # dependency on B by downloading the package from PyPI. If package B is
    # installed first this is avoided.
    with open(os.path.join(temp_dir, EXTRA_PACKAGES_FILE), 'wt') as f:
      for package in local_packages:
        f.write('%s\n' % os.path.basename(package))
    staged_path = FileSystems.join(staging_location, EXTRA_PACKAGES_FILE)
    # Note that the caller of this function is responsible for deleting the
    # temporary folder where all temp files are created, including this one.
    self.file_handler.file_copy(
        os.path.join(temp_dir, EXTRA_PACKAGES_FILE), staged_path)
    resources.append(EXTRA_PACKAGES_FILE)

    return resources

  def _get_python_executable(self):
    # Allow overriding the python executable to use for downloading and
    # installing dependencies, otherwise use the python executable for
    # the current process.
    python_bin = os.environ.get('BEAM_PYTHON') or sys.executable
    if not python_bin:
      raise ValueError('Could not find Python executable.')
    return python_bin

  def _populate_requirements_cache(self, requirements_file, cache_dir):
    # The 'pip download' command will not download again if it finds the
    # tarball with the proper version already present.
    # It will get the packages downloaded in the order they are presented in
    # the requirements file and will not download package dependencies.
    cmd_args = [
        self._get_python_executable(),
        '-m',
        'pip',
        'download',
        '--dest',
        cache_dir,
        '-r',
        requirements_file,
        # Download from PyPI source distributions.
        '--no-binary',
        ':all:'
    ]
    logging.info('Executing command: %s', cmd_args)
    processes.check_call(cmd_args)

  def _build_setup_package(self, setup_file, temp_dir, build_setup_args=None):
    saved_current_directory = os.getcwd()
    try:
      os.chdir(os.path.dirname(setup_file))
      if build_setup_args is None:
        build_setup_args = [
            self._get_python_executable(),
            os.path.basename(setup_file), 'sdist', '--dist-dir', temp_dir
        ]
      logging.info('Executing command: %s', build_setup_args)
      processes.check_call(build_setup_args)
      output_files = glob.glob(os.path.join(temp_dir, '*.tar.gz'))
      if not output_files:
        raise RuntimeError(
            'File %s not found.' % os.path.join(temp_dir, '*.tar.gz'))
      return output_files[0]
    finally:
      os.chdir(saved_current_directory)

  def _desired_sdk_filename_in_staging_location(self, sdk_location):
    """Returns the name that SDK file should have in the staging location.
    
      Args:
        sdk_location: Full path to SDK file.
      """
    if sdk_location.endswith('.whl'):
      _, wheel_filename = FileSystems.split(sdk_location)
      if wheel_filename.startswith('apache_beam'):
        return wheel_filename
      else:
        raise RuntimeError('Unrecognized SDK wheel file: %s' % sdk_location)
    else:
      return names.DATAFLOW_SDK_TARBALL_FILE

  def _stage_beam_sdk(self, sdk_remote_location, staging_location, temp_dir):
    """Stages a Beam SDK file with the appropriate version.

      Args:
        sdk_remote_location: A URL from which thefile can be downloaded or a
          remote file location. The SDK file can be a tarball or a wheel. Set
          to 'pypi' to download and stage a wheel and source SDK from PyPi.
        staging_location: Location where the SDK file should be copied.
        temp_dir: path to temporary location where the file should be
          downloaded.

      Returns:
        A list of SDK files that were staged to the staging location.

      Raises:
        RuntimeError: if staging was not successful.
      """
    if sdk_remote_location == 'pypi':
      sdk_local_file = self._download_pypi_sdk_package(temp_dir)
      sdk_sources_staged_name = self._desired_sdk_filename_in_staging_location(
          sdk_local_file)
      staged_path = FileSystems.join(staging_location, sdk_sources_staged_name)
      logging.info('Staging SDK sources from PyPI to %s', staged_path)
      self.file_handler.file_copy(sdk_local_file, staged_path)
      staged_sdk_files = [sdk_sources_staged_name]
      try:
        # Stage binary distribution of the SDK, for now on a best-effort basis.
        sdk_local_file = self._download_pypi_sdk_package(
            temp_dir, fetch_binary=True)
        sdk_binary_staged_name = self._desired_sdk_filename_in_staging_location(
            sdk_local_file)
        staged_path = FileSystems.join(staging_location, sdk_binary_staged_name)
        logging.info('Staging binary distribution of the SDK from PyPI to %s',
                     staged_path)
        self.file_handler.file_copy(sdk_local_file, staged_path)
        staged_sdk_files.append(sdk_binary_staged_name)
      except RuntimeError as e:
        logging.warn(
            'Failed to download requested binary distribution '
            'of the SDK: %s', repr(e))

      return staged_sdk_files
    elif self.file_handler.is_remote_path(sdk_remote_location):
      local_download_file = os.path.join(temp_dir, 'beam-sdk.tar.gz')
      self.file_handler.file_download(sdk_remote_location, local_download_file)
      staged_name = self._desired_sdk_filename_in_staging_location(
          sdk_remote_location)
      staged_path = FileSystems.join(staging_location, staged_name)
      logging.info('Staging Beam SDK from %s to %s', sdk_remote_location,
                   staged_path)
      self.file_handler.file_copy(local_download_file, staged_path)
      return [staged_name]
    else:
      raise RuntimeError(
          'The --sdk_location option was used with an unsupported '
          'type of location: %s' % sdk_remote_location)

  def _download_pypi_sdk_package(self,
                                 temp_dir,
                                 fetch_binary=False,
                                 language_version_tag='27',
                                 language_implementation_tag='cp',
                                 abi_tag='cp27mu',
                                 platform_tag='manylinux1_x86_64'):
    """Downloads SDK package from PyPI and returns path to local path."""
    package_name = self.get_sdk_package_name()
    try:
      version = pkg_resources.get_distribution(package_name).version
    except pkg_resources.DistributionNotFound:
      raise RuntimeError('Please set --sdk_location command-line option '
                         'or install a valid {} distribution.'
                         .format(package_name))
    cmd_args = [
        self._get_python_executable(), '-m', 'pip', 'download', '--dest',
        temp_dir,
        '%s==%s' % (package_name, version), '--no-deps'
    ]

    if fetch_binary:
      logging.info('Downloading binary distribtution of the SDK from PyPi')
      # Get a wheel distribution for the SDK from PyPI.
      cmd_args.extend([
          '--only-binary', ':all:', '--python-version', language_version_tag,
          '--implementation', language_implementation_tag, '--abi', abi_tag,
          '--platform', platform_tag
      ])
      # Example wheel: apache_beam-2.4.0-cp27-cp27mu-manylinux1_x86_64.whl
      expected_files = [
          os.path.join(
              temp_dir, '%s-%s-%s%s-%s-%s.whl' % (package_name.replace(
                  '-', '_'), version, language_implementation_tag,
                                                  language_version_tag, abi_tag,
                                                  platform_tag))
      ]
    else:
      logging.info('Downloading source distribtution of the SDK from PyPi')
      cmd_args.extend(['--no-binary', ':all:'])
      expected_files = [
          os.path.join(temp_dir, '%s-%s.zip' % (package_name, version)),
          os.path.join(temp_dir, '%s-%s.tar.gz' % (package_name, version))
      ]

    logging.info('Executing command: %s', cmd_args)
    try:
      processes.check_call(cmd_args)
    except subprocess.CalledProcessError as e:
      raise RuntimeError(repr(e))

    for sdk_file in expected_files:
      if os.path.exists(sdk_file):
        return sdk_file

    raise RuntimeError(
        'Failed to download a distribution for the running SDK. '
        'Expected either one of %s to be found in the download folder.' %
        (expected_files))

  def stage_job_resources(self,
                          options,
                          build_setup_args=None,
                          temp_dir=None,
                          populate_requirements_cache=None,
                          staging_location=None):
    """For internal use only; no backwards-compatibility guarantees.

      Creates (if needed) and stages job resources to options.staging_location.

      Args:
        options: Command line options. More specifically the function will
          expect staging_location, requirements_file, setup_file, and
          save_main_session options to be present.
        build_setup_args: A list of command line arguments used to build a setup
          package. Used only if options.setup_file is not None. Used only for
          testing.
        temp_dir: Temporary folder where the resource building can happen. If
          None then a unique temp directory will be created. Used only for
          testing.
        populate_requirements_cache: Callable for populating the requirements
          cache. Used only for testing.
        staging_location: Location to stage the file.

      Returns:
        A list of file names (no paths) for the resources staged. All the files
        are assumed to be staged at staging_location.

      Raises:
        RuntimeError: If files specified are not found or error encountered
        while trying to create the resources (e.g., build a setup package).
      """
    temp_dir = temp_dir or tempfile.mkdtemp()
    resources = []

    setup_options = options.view_as(SetupOptions)
    # Make sure that all required options are specified.
    if staging_location is None:
      raise RuntimeError('The staging_location must be specified.')

    # Stage a requirements file if present.
    if setup_options.requirements_file is not None:
      if not os.path.isfile(setup_options.requirements_file):
        raise RuntimeError(
            'The file %s cannot be found. It was specified in the '
            '--requirements_file command line option.' %
            setup_options.requirements_file)
      staged_path = FileSystems.join(staging_location, REQUIREMENTS_FILE)
      self.file_handler.file_copy(setup_options.requirements_file, staged_path)
      resources.append(REQUIREMENTS_FILE)
      requirements_cache_path = (
          os.path.join(tempfile.gettempdir(), 'dataflow-requirements-cache')
          if setup_options.requirements_cache is None else
          setup_options.requirements_cache)
      # Populate cache with packages from requirements and stage the files
      # in the cache.
      if not os.path.exists(requirements_cache_path):
        os.makedirs(requirements_cache_path)
      (populate_requirements_cache if populate_requirements_cache else
       self._populate_requirements_cache)(setup_options.requirements_file,
                                          requirements_cache_path)
      for pkg in glob.glob(os.path.join(requirements_cache_path, '*')):
        self.file_handler.file_copy(
            pkg, FileSystems.join(staging_location, os.path.basename(pkg)))
        resources.append(os.path.basename(pkg))

    # Handle a setup file if present.
    # We will build the setup package locally and then copy it to the staging
    # location because the staging location is a remote path and the file cannot
    # be created directly there.
    if setup_options.setup_file is not None:
      if not os.path.isfile(setup_options.setup_file):
        raise RuntimeError(
            'The file %s cannot be found. It was specified in the '
            '--setup_file command line option.' % setup_options.setup_file)
      if os.path.basename(setup_options.setup_file) != 'setup.py':
        raise RuntimeError(
            'The --setup_file option expects the full path to a file named '
            'setup.py instead of %s' % setup_options.setup_file)
      tarball_file = self._build_setup_package(setup_options.setup_file,
                                               temp_dir, build_setup_args)
      staged_path = FileSystems.join(staging_location, WORKFLOW_TARBALL_FILE)
      self.file_handler.file_copy(tarball_file, staged_path)
      resources.append(WORKFLOW_TARBALL_FILE)

    # Handle extra local packages that should be staged.
    if setup_options.extra_packages is not None:
      resources.extend(
          self._stage_extra_packages(
              setup_options.extra_packages, staging_location,
              temp_dir=temp_dir))

    # Pickle the main session if requested.
    # We will create the pickled main session locally and then copy it to the
    # staging location because the staging location is a remote path and the
    # file cannot be created directly there.
    if setup_options.save_main_session:
      pickled_session_file = os.path.join(temp_dir,
                                          names.PICKLED_MAIN_SESSION_FILE)
      pickler.dump_session(pickled_session_file)
      staged_path = FileSystems.join(staging_location,
                                     names.PICKLED_MAIN_SESSION_FILE)
      self.file_handler.file_copy(pickled_session_file, staged_path)
      resources.append(names.PICKLED_MAIN_SESSION_FILE)

    if hasattr(setup_options, 'sdk_location'):

      if (setup_options.sdk_location == 'default'
         ) or self.file_handler.is_remote_path(setup_options.sdk_location):
        # If --sdk_location is not specified then the appropriate package
        # will be obtained from PyPI (https://pypi.python.org) based on the
        # version of the currently running SDK. If the option is
        # present then no version matching is made and the exact URL or path
        # is expected.
        #
        # Unit tests running in the 'python setup.py test' context will
        # not have the sdk_location attribute present and therefore we
        # will not stage SDK.
        sdk_remote_location = 'pypi' if (setup_options.sdk_location == 'default'
                                        ) else setup_options.sdk_location
        resources.extend(
            self._stage_beam_sdk(sdk_remote_location, staging_location,
                                 temp_dir))
      else:
        # This branch is also used by internal tests running with the SDK built
        # at head.
        if os.path.isdir(setup_options.sdk_location):
          # TODO(angoenka): remove reference to Dataflow
          sdk_path = os.path.join(setup_options.sdk_location,
                                  names.DATAFLOW_SDK_TARBALL_FILE)
        else:
          sdk_path = setup_options.sdk_location

        if os.path.isfile(sdk_path):
          logging.info('Copying Beam SDK "%s" to staging location.', sdk_path)
          staged_path = FileSystems.join(
              staging_location,
              self._desired_sdk_filename_in_staging_location(
                  setup_options.sdk_location))
          self.file_handler.file_copy(sdk_path, staged_path)
          _, sdk_staged_filename = FileSystems.split(staged_path)
          resources.append(sdk_staged_filename)
        else:
          if setup_options.sdk_location == 'default':
            raise RuntimeError('Cannot find default Beam SDK tar file "%s"',
                               sdk_path)
          elif not setup_options.sdk_location:
            logging.info('Beam SDK will not be staged since --sdk_location '
                         'is empty.')
          else:
            raise RuntimeError(
                'The file "%s" cannot be found. Its location was specified by '
                'the --sdk_location command-line option.' % sdk_path)

    # Delete all temp files created while staging job resources.
    shutil.rmtree(temp_dir)
    return resources

  def get_sdk_package_name(self):
    """For internal use only; no backwards-compatibility guarantees.
    
      Returns the PyPI package name to be staged."""
    return BEAM_PACKAGE_NAME
