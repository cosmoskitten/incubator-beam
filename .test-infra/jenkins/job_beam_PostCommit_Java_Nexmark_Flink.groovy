/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import common_job_properties

// This job runs the suite of ValidatesRunner tests against the Flink runner.
job('beam_PostCommit_Java_Nexmark_Flink') {
  description('Runs the Nexmark suite on the Flink runner.')

  // Set common parameters.
  common_job_properties.setTopLevelMainJobProperties(delegate, 'master', 240)

  // Sets that this is a PostCommit job.
  common_job_properties.setPostCommit(delegate)

  // Allows triggering this build against pull requests.
  common_job_properties.enablePhraseTriggeringFromPullRequest(
    delegate,
    'Apache Flink Runner Nexmark Tests',
    'Run Flink Nexmark')

  // Gradle goals for this job.
  steps {
    shell('echo *** RUN NEXMARK IN BATCH MODE USING FLINK RUNNER ***')
    gradle {
      rootBuildScriptDir(common_job_properties.checkoutDir)
      tasks(':beam-sdks-java-nexmark:run')
      common_job_properties.setGradleSwitches(delegate)
      switches('-Pnexmark.runner=":beam-runners-flink_2.11"' +
              ' -Pnexmark.args="' +
              '        --sinkType=BIGQUERY\n' +
              '        --runner=FlinkRunner\n' +
              '        --streaming=false\n' +
              '        --suite=SMOKE\n' +
              '        --streamTimeout=60\n'  +
              '        --manageResources=false\n' +
              '        --monitorJobs=true\n' +
              '        --flinkMaster=local"')
    }
    shell('echo *** RUN NEXMARK IN STREAMING MODE USING FLINK RUNNER ***')
    gradle {
      rootBuildScriptDir(common_job_properties.checkoutDir)
      tasks(':beam-sdks-java-nexmark:run')
      common_job_properties.setGradleSwitches(delegate)
      switches('-Pnexmark.runner=":beam-runners-flink_2.11"' +
              ' -Pnexmark.args="' +
              '        --sinkType=BIGQUERY\n' +
              '        --runner=FlinkRunner\n' +
              '        --streaming=true\n' +
              '        --suite=SMOKE\n' +
              '        --streamTimeout=60\n'  +
              '        --manageResources=false\n' +
              '        --monitorJobs=true\n' +
              '        --flinkMaster=local"')
    }
  }
}
