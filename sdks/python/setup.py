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

"""Apache Beam SDK for Python setup file."""

from __future__ import absolute_import
from __future__ import print_function

import os
import platform
import sys
import warnings
from distutils.version import StrictVersion
from distutils.errors import DistutilsError

# Pylint and isort disagree here.
# pylint: disable=ungrouped-imports
import setuptools
from pkg_resources import DistributionNotFound
from pkg_resources import get_distribution
from pkg_resources import normalize_path
from pkg_resources import to_filename
from setuptools import Command
from setuptools.command.build_py import build_py
from setuptools.command.develop import develop
from setuptools.command.egg_info import egg_info
from setuptools.command.sdist import sdist
from setuptools.command.test import test


class mypy(Command):
  user_options = []

  def initialize_options(self):
    """Abstract method that is required to be overwritten"""

  def finalize_options(self):
    """Abstract method that is required to be overwritten"""

  def get_project_path(self, include_dists=[]):
      # with_2to3 = six.PY3 and getattr(self.distribution, 'use_2to3', False)
      #
      # if with_2to3:
      #     # If we run 2to3 we can not do this inplace:
      #
      #     # Ensure metadata is up-to-date
      #     self.reinitialize_command('build_py', inplace=0)
      #     self.run_command('build_py')
      #     bpy_cmd = self.get_finalized_command("build_py")
      #     build_path = normalize_path(bpy_cmd.build_lib)
      #
      #     # Build extensions
      #     self.reinitialize_command('egg_info', egg_base=build_path)
      #     self.run_command('egg_info')
      #
      #     self.reinitialize_command('build_ext', inplace=0)
      #     self.run_command('build_ext')
      # else:
      # Without 2to3 inplace works fine:
      self.run_command('egg_info')

      # Build extensions in-place
      self.reinitialize_command('build_ext', inplace=1)
      self.run_command('build_ext')

      ei_cmd = self.get_finalized_command("egg_info")

      project_path = normalize_path(ei_cmd.egg_base)
      return os.path.join(project_path, to_filename(ei_cmd.egg_name))

  def run(self):
    import subprocess
    args = ['mypy', self.get_project_path()]
    result = subprocess.call(args)
    if result != 0:
      raise DistutilsError()


def get_version():
  global_names = {}
  exec(  # pylint: disable=exec-used
      open(os.path.join(
          os.path.dirname(os.path.abspath(__file__)),
          'apache_beam/version.py')
          ).read(),
      global_names
  )
  return global_names['__version__']


PACKAGE_NAME = 'apache-beam'
PACKAGE_VERSION = get_version()
PACKAGE_DESCRIPTION = 'Apache Beam SDK for Python'
PACKAGE_URL = 'https://beam.apache.org'
PACKAGE_DOWNLOAD_URL = 'https://pypi.python.org/pypi/apache-beam'
PACKAGE_AUTHOR = 'Apache Software Foundation'
PACKAGE_EMAIL = 'dev@beam.apache.org'
PACKAGE_KEYWORDS = 'apache beam'
PACKAGE_LONG_DESCRIPTION = '''
Apache Beam is a unified programming model for both batch and streaming
data processing, enabling efficient execution across diverse distributed
execution engines and providing extensibility points for connecting to
different technologies and user communities.
'''

REQUIRED_PIP_VERSION = '7.0.0'
_PIP_VERSION = get_distribution('pip').version
if StrictVersion(_PIP_VERSION) < StrictVersion(REQUIRED_PIP_VERSION):
  warnings.warn(
      "You are using version {0} of pip. " \
      "However, version {1} is recommended.".format(
          _PIP_VERSION, REQUIRED_PIP_VERSION
      )
  )


REQUIRED_CYTHON_VERSION = '0.28.1'
try:
  _CYTHON_VERSION = get_distribution('cython').version
  if StrictVersion(_CYTHON_VERSION) < StrictVersion(REQUIRED_CYTHON_VERSION):
    warnings.warn(
        "You are using version {0} of cython. " \
        "However, version {1} is recommended.".format(
            _CYTHON_VERSION, REQUIRED_CYTHON_VERSION
        )
    )
except DistributionNotFound:
  # do nothing if Cython is not installed
  pass

# Currently all compiled modules are optional  (for performance only).
if platform.system() == 'Windows':
  # Windows doesn't always provide int64_t.
  cythonize = lambda *args, **kwargs: []
else:
  try:
    # pylint: disable=wrong-import-position
    from Cython.Build import cythonize
  except ImportError:
    cythonize = lambda *args, **kwargs: []

REQUIRED_PACKAGES = [
    'avro>=1.8.1,<2.0.0; python_version < "3.0"',
    'avro-python3>=1.8.1,<2.0.0; python_version >= "3.0"',
    'crcmod>=1.7,<2.0',
    'dill>=0.2.9,<0.2.10',
    'fastavro>=0.21.4,<0.22',
    'future>=0.16.0,<1.0.0',
    'futures>=3.2.0,<4.0.0; python_version < "3.0"',
    'grpcio>=1.12.1,<2',
    'hdfs>=2.1.0,<3.0.0',
    'httplib2>=0.8,<=0.12.0',
    'mock>=1.0.1,<3.0.0',
    'pymongo>=3.8.0,<4.0.0',
    'oauth2client>=2.0.1,<4',
    'protobuf>=3.5.0.post1,<4',
    # [BEAM-6287] pyarrow is not supported on Windows for Python 2
    ('pyarrow>=0.11.1,<0.15.0; python_version >= "3.0" or '
     'platform_system != "Windows"'),
    'pydot>=1.2.0,<2',
    'python-dateutil>=2.8.0,<3',
    'pytz>=2018.3',
    # [BEAM-5628] Beam VCF IO is not supported in Python 3.
    'pyvcf>=0.6.8,<0.7.0; python_version < "3.0"',
    'pyyaml>=3.12,<4.0.0',
    # fixes and additions have been made since typing 3.5
    'typing>=3.7.0,<3.8.0; python_version < "3.8.0"',
    'typing-extensions>=3.7.0,<3.8.0; python_version < "3.8.0"',
    ]

REQUIRED_TEST_PACKAGES = [
    'nose>=1.3.7',
    'nose_xunitmp>=0.4.1',
    'numpy>=1.14.3,<2',
    'pandas>=0.23.4,<0.25',
    'parameterized>=0.6.0,<0.7.0',
    'pyhamcrest>=1.9,<2.0',
    'tenacity>=5.0.2,<6.0',
    ]

GCP_REQUIREMENTS = [
    'cachetools>=3.1.0,<4',
    'google-apitools>=0.5.28,<0.5.29',
    # [BEAM-4543] googledatastore is not supported in Python 3.
    'proto-google-cloud-datastore-v1>=0.90.0,<=0.90.4; python_version < "3.0"',
    # [BEAM-4543] googledatastore is not supported in Python 3.
    'googledatastore>=7.0.1,<7.1; python_version < "3.0"',
    'google-cloud-datastore>=1.7.1,<1.8.0',
    'google-cloud-pubsub>=0.39.0,<0.40.0',
    # GCP packages required by tests
    'google-cloud-bigquery>=1.6.0,<1.18.0',
    'google-cloud-core>=0.28.1,<2',
    'google-cloud-bigtable>=0.31.1,<0.33.0',
]


# We must generate protos after setup_requires are installed.
def generate_protos_first(original_cmd):
  try:
    # See https://issues.apache.org/jira/browse/BEAM-2366
    # pylint: disable=wrong-import-position
    import gen_protos

    class cmd(original_cmd, object):
      def run(self):
        gen_protos.generate_proto_files()
        super(cmd, self).run()
    return cmd
  except ImportError:
    warnings.warn("Could not import gen_protos, skipping proto generation.")
    return original_cmd


python_requires = '>=2.7,!=3.0.*,!=3.1.*,!=3.2.*,!=3.3.*,!=3.4.*'

if sys.version_info[0] == 3:
  warnings.warn(
      'Some syntactic constructs of Python 3 are not yet fully supported by '
      'Apache Beam.')

setuptools.setup(
    name=PACKAGE_NAME,
    version=PACKAGE_VERSION,
    description=PACKAGE_DESCRIPTION,
    long_description=PACKAGE_LONG_DESCRIPTION,
    url=PACKAGE_URL,
    download_url=PACKAGE_DOWNLOAD_URL,
    author=PACKAGE_AUTHOR,
    author_email=PACKAGE_EMAIL,
    packages=setuptools.find_packages(),
    package_data={'apache_beam': [
        '*/*.pyx', '*/*/*.pyx', '*/*.pxd', '*/*/*.pxd', 'testing/data/*.yaml',
        'portability/api/*.yaml']},
    ext_modules=cythonize([
        'apache_beam/**/*.pyx',
        'apache_beam/coders/coder_impl.py',
        'apache_beam/metrics/execution.py',
        'apache_beam/runners/common.py',
        'apache_beam/runners/worker/logger.py',
        'apache_beam/runners/worker/opcounters.py',
        'apache_beam/runners/worker/operations.py',
        'apache_beam/transforms/cy_combiners.py',
        'apache_beam/utils/counters.py',
        'apache_beam/utils/windowed_value.py',
    ]),
    install_requires=REQUIRED_PACKAGES,
    python_requires=python_requires,
    test_suite='nose.collector',
    tests_require=REQUIRED_TEST_PACKAGES,
    extras_require={
        'docs': ['Sphinx>=1.5.2,<2.0'],
        'test': REQUIRED_TEST_PACKAGES,
        'gcp': GCP_REQUIREMENTS,
    },
    zip_safe=False,
    # PyPI package information.
    classifiers=[
        'Intended Audience :: End Users/Desktop',
        'License :: OSI Approved :: Apache Software License',
        'Operating System :: POSIX :: Linux',
        'Programming Language :: Python :: 2.7',
        'Programming Language :: Python :: 3.5',
        'Programming Language :: Python :: 3.6',
        'Programming Language :: Python :: 3.7',
        'Topic :: Software Development :: Libraries',
        'Topic :: Software Development :: Libraries :: Python Modules',
    ],
    license='Apache License, Version 2.0',
    keywords=PACKAGE_KEYWORDS,
    entry_points={
        'nose.plugins.0.10': [
            'beam_test_plugin = test_config:BeamTestPlugin',
        ]},
    cmdclass={
        'build_py': generate_protos_first(build_py),
        'develop': generate_protos_first(develop),
        'egg_info': generate_protos_first(egg_info),
        'sdist': generate_protos_first(sdist),
        'test': generate_protos_first(test),
        'mypy': generate_protos_first(mypy),
    },
)
