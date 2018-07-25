#!/bin/bash
#
#    Licensed to the Apache Software Foundation (ASF) under one or more
#    contributor license agreements.  See the NOTICE file distributed with
#    this work for additional information regarding copyright ownership.
#    The ASF licenses this file to You under the Apache License, Version 2.0
#    (the "License"); you may not use this file except in compliance with
#    the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#

###########################################################################
#
# This script will be run by Jenkins as a post commit test. In order to run
# locally make the following changes:
#
# GCS_LOCATION -> Temporary location to use for service tests.
# PROJECT      -> Project name to use for service jobs.
#


###########################################################################
# Usage check.

if (( $# < 2 )); then
  printf "Usage: \n$> ./scripts/run_postcommit.sh <test_type> <pipeline_type> [gcp_location] [gcp_project]"
  printf "\n\ttest_type: [required] ValidatesRunner or IT"
  printf "\n\tpipeline_type: [required] streaming or batch"
  printf "\n\tgcp_location: [optional] A gs:// path to stage artifacts and output results"
  printf "\n\tgcp_project: [optional] A GCP project to run Dataflow pipelines\n"
  exit 1
fi

set -e
set -v


###########################################################################
# Build tarball and set pipeline options.

# Check that the script is running in a known directory.
if [[ $PWD != *sdks/python* ]]; then
  echo 'Unable to locate Apache Beam Python SDK root directory'
  exit 1
fi

# Go to the Apache Beam Python SDK root
if [[ "*sdks/python" != $PWD ]]; then
  cd $(pwd | sed 's/sdks\/python.*/sdks\/python/')
fi

# Where to store integration test outputs.
GCS_LOCATION=${3:-gs://temp-storage-for-end-to-end-tests}

PROJECT=${4:-apache-beam-testing}

# Create a tarball
python setup.py sdist

SDK_LOCATION=$(find dist/apache-beam-*.tar.gz)

# Install test dependencies for ValidatesRunner tests.
echo "pyhamcrest" > postcommit_requirements.txt
echo "mock" >> postcommit_requirements.txt

# Options used to run testing pipeline on Cloud Dataflow Service.
PIPELINE_OPTIONS=(
  "--runner=TestDataflowRunner"
  "--project=$PROJECT"
  "--staging_location=$GCS_LOCATION/staging-it"
  "--temp_location=$GCS_LOCATION/temp-it"
  "--output=$GCS_LOCATION/py-it-cloud/output"
  "--sdk_location=$SDK_LOCATION"
  "--requirements_file=postcommit_requirements.txt"
  "--num_workers=1"
  "--sleep_secs=20"
)

# Add streaming flag if specified.
if [[ "$2" = "streaming" ]]; then
  echo ">>> Set test pipeline to streaming"
  PIPELINE_OPTIONS+=("--streaming")
else
  echo ">>> Set test pipeline to batch"
fi


###########################################################################
# Run tests on the Google Cloud Dataflow service and validate that jobs
# finish successfully.

JOINED_OPTS=$(IFS=" " ; echo "${PIPELINE_OPTIONS[*]}")

echo ">>> RUNNING TEST DATAFLOW RUNNER $1 tests"
python setup.py nosetests \
  --attr $1 \
  --nocapture \
  --processes=8 \
  --process-timeout=2000 \
  --test-pipeline-options="$JOINED_OPTS"
