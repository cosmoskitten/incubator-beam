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
import CommonTestProperties
import LoadTestsBuilder as loadTestsBuilder
import PhraseTriggeringPostCommitBuilder
import CronJobBuilder

def now = new Date().format("MMddHHmmss", TimeZone.getTimeZone('UTC'))

def loadTestConfigurations = {context -> [
        [
                title        : 'GroupByKey Python Load test: reiterate 4 times 10kB values',
                itClass      :  'apache_beam.testing.load_tests.group_by_key_test:GroupByKeyTest.testGroupByKey',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        project              : 'apache-beam-testing',
                        job_name             : 'load-tests-python-dataflow-batch-gbk-6-' + now,
                        temp_location        : 'gs://temp-storage-for-perf-tests/loadtests',
                        publish_to_big_query : true,
                        metrics_dataset      : loadTestsBuilder.setContextualDatasetName('load_test', context),
                        metrics_table        : "python_dataflow_batch_gbk_6",
                        input_options        : '\'{"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90,' +
                                '"num_hot_keys": 200,' +
                                '"hot_key_fraction": 1}\'',
                        fanout               : 1,
                        iterations           : 4,
                        max_num_workers      : 5,
                        num_workers          : 5,
                        autoscaling_algorithm: "NONE"
                ]
        ],
        [
                title        : 'GroupByKey Python Load test: reiterate 4 times 2MB values',
                itClass      :  'apache_beam.testing.load_tests.group_by_key_test:GroupByKeyTest.testGroupByKey',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        project              : 'apache-beam-testing',
                        job_name             : 'load-tests-python-dataflow-batch-gbk-7-' + now,
                        temp_location        : 'gs://temp-storage-for-perf-tests/loadtests',
                        publish_to_big_query : true,
                        metrics_dataset      : 'load_test',
                        metrics_table        : 'python_dataflow_batch_gbk_7',
                        input_options        : '\'{"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90,' +
                                '"num_hot_keys": 10,' +
                                '"hot_key_fraction": 1}\'',
                        fanout               : 1,
                        iterations           : 4,
                        max_num_workers      : 5,
                        num_workers          : 5,
                        autoscaling_algorithm: 'NONE'
                ]
        ]
]}

def batchLoadTestJob = { scope, triggeringContext ->
    scope.description('Runs Python GBK reiterate load tests on Dataflow runner in batch mode')
    commonJobProperties.setTopLevelMainJobProperties(scope, 'master', 240)

    for (testConfiguration in loadTestConfigurations(triggeringContext)) {
        loadTestsBuilder.loadTest(scope, testConfiguration.title, testConfiguration.runner, CommonTestProperties.SDK.PYTHON, testConfiguration.jobProperties, testConfiguration.itClass, triggeringContext)
    }
}

CronJobBuilder.cronJob('beam_LoadTests_Python_GBK_reiterate_Dataflow_Batch', 'H 14 * * *', this) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.POST_COMMIT)
}

PhraseTriggeringPostCommitBuilder.postCommitJob(
        'beam_LoadTests_Python_GBK_reiterate_Dataflow_Batch',
        'Run Load Tests Python GBK reiterate Dataflow Batch',
        'Load Tests Python GBK reiterate Dataflow Batch suite',
        this
) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.PR)
}
