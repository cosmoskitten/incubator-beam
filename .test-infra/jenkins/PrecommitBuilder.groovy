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

/** This class defines PreCommitBuilder.build() helper for defining pre-comit jobs. */
class PrecommitBuilder {
  /** scope 'this' parameter from top-level script; used for binding Job DSL methods. */
  Object scope

  /** Base name for each post-commit suite job, i.e. 'Go'. */
  String nameBase

  /**  The Gradle task to execute. */
  String gradleTask

  /** Overall job timeout. */
  int timeoutMins = 90

  /**
   * Define a set of pre-commit jobs.
   *
   * @param additionalCustomization Job DSL closure with additional customization to apply to the job.
   */
  void build(Closure additionalCustomization = {}) {
    defineCronJob additionalCustomization
    defineCommitJob additionalCustomization
    definePhraseJob additionalCustomization
  }

  /** Create a pre-commit job which runs on a daily schedule. */
  private void defineCronJob(Closure additionalCustomization) {
    def job = createBaseJob 'Cron'
    job.with {
      description buildDescription('on a daily schedule.')
      common_job_properties.setPostCommit delegate // TODO: Rename method as only defines cron
    }
    job.with additionalCustomization
  }

  /** Create a pre-commit job which runs on every commit to a PR. */
  private void defineCommitJob(Closure additionalCustomization) {
    def job = createBaseJob 'Commit'
    job.with {
      description buildDescription('for each commit push.')
      concurrentBuild()
      common_job_properties.setPullRequestBuildTrigger delegate, githubUiHint()
      // TODO: Define filters
    }
    job.with additionalCustomization
  }

  private void definePhraseJob(Closure additionalCustomization) {
    def job = createBaseJob 'Phrase'
    job.with {
      description buildDescription("on trigger phrase '${buildTriggerPhrase()}.)")
      concurrentBuild()
      common_job_properties.setPullRequestBuildTrigger delegate, githubUiHint(), buildTriggerPhrase()
    }
    job.with additionalCustomization
  }

  private Object createBaseJob(nameSuffix) {
    return scope.job("beam_PreCommit_${nameBase}_${nameSuffix}") {
      // TODO: Update branch
      common_job_properties.setTopLevelMainJobProperties(delegate, 'master', timeoutMins)
      steps {
        gradle {
          rootBuildScriptDir(common_job_properties.checkoutDir)
          tasks(gradleTask)
          common_job_properties.setGradleSwitches(delegate)
        }
      }
    }
  }

  /** The magic phrase used to trigger the job when posted as a PR comment. */
  private String buildTriggerPhrase() {
    return "Run ${nameBase} PreCommit"
  }

  /** A human-readable description which will be used as the base of all suite jobs. */
  private buildDescription(String triggerDescription) {
    return "Runs ${nameBase} PreCommit tests ${triggerDescription}"
  }

  /** The Jenkins job name to display in GitHub. */
  private String githubUiHint() {
    "${nameBase} PreCommit (\"${buildTriggerPhrase()}\")"
  }
}
