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

// These jobs list details about each beam runner
[5].each {
  def machine = "beam${it}"
  println machine
  job("beam_Inventory_${machine}") {
    parameters {
      nodeParam('TEST_HOST') {
        defaultNodes([machine])
        allowedNodes([machine])
      }
    }
    label('beam')
    triggers {
      githubPullRequest {
        triggerPhrase("run Inventory $machine")
        onlyTriggerPhrase()
        permitAll()
      }
    }
    mavenInstallation('Maven 3.5.2')
    steps {
      shell('mvn -v || echo "mvn not found"')
      shell('gradle -v || echo "gradle not found"')
      shell('gcloud -v || echo "gcloud not found"')
      shell('kubectl version || echo "kubectl not found"')
      shell('virtualenv -p python2.7 test2 && . ./test2/bin/activate && python --version && deactivate || echo "python 2.7 not found"')
      shell('virtualenv -p python3 test3 && . ./test3/bin/activate && python --version && deactivate || echo "python 3 not found"')
      python {
        pythonName('System-CPython-2.7')
        command('python --version')
      }
      python {
        pythonName('System-CPython-3.3')
        command('python --version')
      }
    }
  }
}
