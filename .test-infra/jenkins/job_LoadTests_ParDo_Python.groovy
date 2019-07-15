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
import LoadTestsBuilder as loadTestsBuilder
import PhraseTriggeringPostCommitBuilder

def now = new Date().format("MMddHHmmss", TimeZone.getTimeZone('UTC'))

def loadTestConfigurations = { datasetName -> [
        [
                title        : 'ParDo Python Load test: 2GB 100 byte records 10 times',
                itClass      : 'apache_beam.testing.load_tests.pardo_test:ParDoTest.testParDo',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        job_name             : 'load-tests-python-dataflow-batch-pardo-1-' + now,
                        project              : 'apache-beam-testing',
                        temp_location        : 'gs://temp-storage-for-perf-tests/smoketests',
                        publish_to_big_query : true,
                        metrics_dataset      : datasetName,
                        metrics_table        : 'python_dataflow_batch_pardo_1',
                        input_options        : '\'{' +
                                '"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90}\'',
                        iterations           : 10,
                        number_of_counter_operations: 0,
                        number_of_counters   : 0,
                        num_workers          : 5,
                        autoscaling_algorithm: 'NONE',
                ]
        ],
        [
                title        : 'ParDo Python Load test: 2GB 100 byte records 200 times',
                itClass      : 'apache_beam.testing.load_tests.pardo_test:ParDoTest.testParDo',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        job_name             : 'load-tests-python-dataflow-batch-pardo-2-' + now,
                        project              : 'apache-beam-testing',
                        temp_location        : 'gs://temp-storage-for-perf-tests/smoketests',
                        publish_to_big_query : true,
                        metrics_dataset      : datasetName,
                        metrics_table        : 'python_dataflow_batch_pardo_2',
                        input_options        : '\'{' +
                                '"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90}\'',
                        iterations           : 200,
                        number_of_counter_operations: 0,
                        number_of_counters   : 0,
                        num_workers          : 5,
                        autoscaling_algorithm: 'NONE',
                ]
        ],
        [
                title        : 'ParDo Python Load test: 2GB 100 byte records 10 counters',
                itClass      : 'apache_beam.testing.load_tests.pardo_test:ParDoTest.testParDo',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        job_name             : 'load-tests-python-dataflow-batch-pardo-3-' + now,
                        project              : 'apache-beam-testing',
                        temp_location        : 'gs://temp-storage-for-perf-tests/smoketests',
                        publish_to_big_query : true,
                        metrics_dataset      : datasetName,
                        metrics_table        : 'python_dataflow_batch_pardo_3',
                        input_options        : '\'{' +
                                '"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90}\'',
                        iterations           : 10,
                        number_of_counter_operations: 10,
                        number_of_counters   : 1,
                        num_workers          : 5,
                        autoscaling_algorithm: 'NONE',
                ]
        ],
]}

def loadTestConfigurationManyCounters = { datasetName -> [
        [
                title        : 'ParDo Python Load test: 2GB 100 byte records 100 counters',
                itClass      : 'apache_beam.testing.load_tests.pardo_test:ParDoTest.testParDo',
                runner       : CommonTestProperties.Runner.DATAFLOW,
                jobProperties: [
                        job_name             : 'load-tests-python-dataflow-batch-pardo-4-' + now,
                        project              : 'apache-beam-testing',
                        temp_location        : 'gs://temp-storage-for-perf-tests/smoketests',
                        publish_to_big_query : true,
                        metrics_dataset      : datasetName,
                        metrics_table        : 'python_dataflow_batch_pardo_4',
                        input_options        : '\'{' +
                                '"num_records": 20000000,' +
                                '"key_size": 10,' +
                                '"value_size": 90}\'',
                        iterations           : 10,
                        number_of_counter_operations: 100,
                        number_of_counters   : 1,
                        num_workers          : 5,
                        autoscaling_algorithm: 'NONE',
                ]
        ],
]}

def batchLoadTestJob = { scope, triggeringContext, testConfigurations ->
    scope.description('Runs Python ParDo load tests on Dataflow runner in batch mode')
    commonJobProperties.setTopLevelMainJobProperties(scope, 'master', 120)

    def datasetName = loadTestsBuilder.getBigQueryDataset('load_test', triggeringContext)
    for (testConfiguration in testConfigurations(datasetName)) {
        loadTestsBuilder.loadTest(scope, testConfiguration.title, testConfiguration.runner, CommonTestProperties.SDK.PYTHON, testConfiguration.jobProperties, testConfiguration.itClass)
    }
}

PhraseTriggeringPostCommitBuilder.postCommitJob(
        'beam_LoadTests_Python_ParDo_Dataflow_Batch',
        'Run Python Load Tests ParDo Dataflow Batch',
        'Load Tests Python ParDo Dataflow Batch suite',
        this
) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.PR, loadTestConfigurations)
}

CronJobBuilder.cronJob('beam_LoadTests_Python_ParDo_Dataflow_Batch', 'H 13 * * *', this) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.POST_COMMIT, loadTestConfigurations)
}

PhraseTriggeringPostCommitBuilder.postCommitJob(
        'beam_LoadTests_Python_ParDo_Dataflow_Many_Counters_Batch',
        'Run Python Load Tests ParDo Dataflow Batch',
        'Load Test Python ParDo Dataflow Batch many counters',
        this
) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.PR, loadTestConfigurationManyCounters)
}

CronJobBuilder.cronJob('beam_LoadTests_Python_ParDo_Dataflow_Many_Counters_Batch', 'H 13 * * *', this) {
    batchLoadTestJob(delegate, CommonTestProperties.TriggeringContext.POST_COMMIT, loadTestConfigurationManyCounters)
}
