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
# This script will be run by Jenkins as a Python dependency test.

set -e
set -v

PROJECT_ID='apache-beam-testing'
DATASET_ID='beam_dependency_states'
PYTHON_DEP_TABLE_ID='python_dependency_states'
JAVA_DEP_TABLE_ID='java_dependency_states'
REPORT_EXPLANATION="<h4>In Beam, we make a best-effort attempt at keeping all dependencies up-to-date.
A dependency update is high priority if it satisfies one of following criteria:
    1. It has major versions update available;
          e.g. org.assertj:assertj-core 2.5.0 -> 3.10.0
    2. or, it is over 3 minor versions behind the latest version;
          e.g. org.tukaani:xz 1.5 -> 1.8
    3. or, the current version is behind the later version for over 180 days.
          e.g. com.google.auto.service:auto-service 2014-10-24 -> 2017-12-11
In the future, issues will be filed and tracked for these automatically, but in the meantime you can search for existing issues or open a new one.
Read more about our dependency update policy: <a href=\"https://docs.google.com/document/d/15m1MziZ5TNd9rh_XN0YYBJfYkt0Oj-Ou9g0KFDPL2aA/edit#\"> Beam Dependency Update Policy </a></h4>"


# Virtualenv for the rest of the script to run setup
/usr/bin/virtualenv dependency/check
. dependency/check/bin/activate
pip install --upgrade google-cloud-bigquery

rm -f build/dependencyUpdates/beam-dependency-check-report.txt

echo "<html><body>" > build/dependencyUpdates/beam-dependency-check-report.html

python $WORKSPACE/src/.test-infra/jenkins/dependency_check/generate_dependency_check_report.py \
build/dependencyUpdates/python_dependency_report.txt \
Python \
$PROJECT_ID \
$DATASET_ID \
$PYTHON_DEP_TABLE_ID

python $WORKSPACE/src/.test-infra/jenkins/dependency_check/generate_dependency_check_report.py \
build/dependencyUpdates/report.txt \
Java \
$PROJECT_ID \
$DATASET_ID \
$JAVA_DEP_TABLE_ID

echo "$REPORT_EXPLANATION </body></html>" >> build/dependencyUpdates/beam-dependency-check-report.html
