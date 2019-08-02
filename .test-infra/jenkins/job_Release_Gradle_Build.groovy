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

import CommonJobProperties as commonJobProperties

job('beam_Release_Gradle_Build') {
    description('Runs gradle build to verify release branches.')

    // Set common parameters.
    commonJobProperties.setTopLevelMainJobProperties(delegate)

    // Allows triggering this build against pull requests.
    commonJobProperties.enablePhraseTriggeringFromPullRequest(
            delegate,
            './gradlew build -PisRelease --scan --stacktrace --no-parallel --continue',
            'Run Dataflow PostRelease')

    steps {
        shell('cd ' + commonJobProperties.checkoutDir +
                ' && bash ./gradlew build -PisRelease --scan --stacktrace --info --continue')
    }
}
