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
package org.apache.beam.sdk.io.hcatalog;

import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.TEST_DATABASE;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.TEST_FILTER;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.TEST_RECORDS_COUNT;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.TEST_TABLE;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.buildHCatRecords;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.getConfigPropertiesAsMap;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.getExpectedRecords;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.getReaderContext;
import static org.apache.beam.sdk.io.hcatalog.test.HCatalogIOTestUtils.insertTestData;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.hadoop.WritableCoder;
import org.apache.beam.sdk.io.hcatalog.HCatalogIO.BoundedHCatalogSource;
import org.apache.beam.sdk.io.hcatalog.test.EmbeddedMetastoreService;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.SourceTestUtils;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableComparator;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.ImmutableList;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hive.hcatalog.data.DefaultHCatRecord;
import org.apache.hive.hcatalog.data.HCatRecord;
import org.apache.hive.hcatalog.data.transfer.ReaderContext;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.model.Statement;

/** Test for HCatalogIO. */
@RunWith(JUnit4.class)
public class HCatalogIOTest implements Serializable {
  private static final PipelineOptions OPTIONS = PipelineOptionsFactory.create();

  @ClassRule public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();

  @Rule public final transient TestPipeline defaultPipeline = TestPipeline.create();

  @Rule public final transient TestPipeline readAfterWritePipeline = TestPipeline.create();

  @Rule public transient ExpectedException thrown = ExpectedException.none();

  @Rule
  public final transient TestRule testDataSetupRule =
      new TestWatcher() {
        @Override
        public Statement apply(final Statement base, final Description description) {
          return new Statement() {
            @Override
            public void evaluate() throws Throwable {
              if (description.getAnnotation(NeedsTestData.class) != null) {
                prepareTestData();
              } else if (description.getAnnotation(NeedsEmptyTestTables.class) != null) {
                reCreateTestTable();
              } else if (description.getAnnotation(NeedsEmptyTestTablesForUnboundedReads.class)
                  != null) {
                reCreateTestTableForUnboundedReads();
              }
              base.evaluate();
            }
          };
        }
      };

  private static EmbeddedMetastoreService service;

  /** Use this annotation to setup complete test data(table populated with records). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD})
  private @interface NeedsTestData {}

  /** Use this annotation to setup complete test data(table populated with unbounded records). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD})
  private @interface NeedsEmptyTestTablesForUnboundedReads {}

  /** Use this annotation to setup test tables alone(empty tables, no records are populated). */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD})
  private @interface NeedsEmptyTestTables {}

  @BeforeClass
  public static void setupEmbeddedMetastoreService() throws IOException {
    service = new EmbeddedMetastoreService(TMP_FOLDER.getRoot().getAbsolutePath());
  }

  @AfterClass
  public static void shutdownEmbeddedMetastoreService() throws Exception {
    if (service != null) {
      service.executeQuery("drop table " + TEST_TABLE);
      service.close();
    }
  }

  /** Perform end-to-end test of Write-then-Read operation. */
  @Test
  @NeedsEmptyTestTables
  public void testWriteThenReadSuccess() {
    defaultPipeline
        .apply(Create.of(buildHCatRecords(TEST_RECORDS_COUNT)))
        .apply(
            HCatalogIO.write()
                .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
                .withDatabase(TEST_DATABASE)
                .withTable(TEST_TABLE)
                .withPartition(new java.util.HashMap<>())
                .withBatchSize(512L));
    defaultPipeline.run();

    PCollection<String> output =
        readAfterWritePipeline
            .apply(
                HCatalogIO.read()
                    .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
                    .withDatabase(TEST_DATABASE)
                    .withTable(TEST_TABLE)
                    .withFilter(TEST_FILTER))
            .apply(
                ParDo.of(
                    new DoFn<HCatRecord, String>() {
                      @ProcessElement
                      public void processElement(ProcessContext c) {
                        c.output(c.element().get(0).toString());
                      }
                    }));
    PAssert.that(output).containsInAnyOrder(getExpectedRecords(TEST_RECORDS_COUNT));
    readAfterWritePipeline.run();
  }

  private Map<String, String> getPartitioned() {
    String loadDate = "2019-05-14T23:28:04.425Z";
    int productType = 1;
    Map<String, String> partitions = new HashMap<>();
    partitions.put("load_date", String.valueOf(loadDate));
    partitions.put("product_type", String.valueOf(productType));
    return partitions;
  }

  /** Perform end-to-end test of Write-then-Read operation. */
  @Test
  @NeedsEmptyTestTablesForUnboundedReads
  public void testWriteThenUnboundedReadSuccess() throws Exception {

    defaultPipeline
        .apply(Create.of(buildHCatRecords(TEST_RECORDS_COUNT)))
        .apply(
            HCatalogIO.write()
                .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
                .withDatabase(TEST_DATABASE)
                .withTable(TEST_TABLE)
                .withPartition(getPartitioned())
                .withBatchSize(512L));
    defaultPipeline.run();

    final HCatalogUnboundedReader reader = HCatalogUnboundedReader.of();
    reader.setConfig(getConfigPropertiesAsMap(service.getHiveConf()));
    reader.setDatabase(TEST_DATABASE);
    reader.setTable(TEST_TABLE);
    reader.setShouldTreatSourceAsBounded(true);

    final PCollection<HCatRecord> records =
        readAfterWritePipeline
            .apply(Create.of(buildReadRequest()))
            .apply(reader)
            .setCoder((Coder) WritableCoder.of(DefaultHCatRecord.class));

    final PCollection<String> output =
        records.apply(
            ParDo.of(
                new DoFn<HCatRecord, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    c.output(c.element().get(0).toString());
                  }
                }));

    PAssert.that(output).containsInAnyOrder(getExpectedRecords(TEST_RECORDS_COUNT));
    readAfterWritePipeline.run();
  }

  private static List<PartitionPoller.ReadRequest> buildReadRequest() {
    List<PartitionPoller.ReadRequest> expected = new ArrayList<>();
    final HCatalogIO.Read read =
        HCatalogIO.read()
            .withDatabase(TEST_DATABASE)
            .withTable(TEST_TABLE)
            .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()));

    final ImmutableList<String> partitions = ImmutableList.of("load_date", "product_type");

    final PartitionPoller.ReadRequest readRequest =
        new PartitionPoller.ReadRequest(
            Duration.millis(15000),
            read,
            new PartitionCreateTimeComparator(),
            new WatermarkTimestampConverter(),
            partitions,
            "load_date");
    expected.add(readRequest);
    return expected;
  }

  private static class PartitionCreateTimeComparator implements SerializableComparator<Partition> {
    @Override
    public int compare(Partition o1, Partition o2) {
      if (o1.getCreateTime() > o2.getCreateTime()) {
        return -1;
      } else if (o1.getCreateTime() < o2.getCreateTime()) {
        return 1;
      }
      return 0;
    }
  }

  private static class WatermarkTimestampConverter
      implements SerializableFunction<String, Instant> {

    @Override
    public Instant apply(String input) {
      return Instant.parse(input);
    }
  }

  /** Test of Write to a non-existent table. */
  @Test
  public void testWriteFailureTableDoesNotExist() {
    thrown.expectCause(isA(UserCodeException.class));
    thrown.expectMessage(containsString("org.apache.hive.hcatalog.common.HCatException"));
    thrown.expectMessage(containsString("NoSuchObjectException"));
    defaultPipeline
        .apply(Create.of(buildHCatRecords(TEST_RECORDS_COUNT)))
        .apply(
            HCatalogIO.write()
                .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
                .withTable("myowntable"));
    defaultPipeline.run();
  }

  /** Test of Write without specifying a table. */
  @Test
  public void testWriteFailureValidationTable() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("withTable() is required");
    HCatalogIO.write()
        .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
        .expand(null);
  }

  /** Test of Write without specifying configuration properties. */
  @Test
  public void testWriteFailureValidationConfigProp() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("withConfigProperties() is required");
    HCatalogIO.write().withTable("myowntable").expand(null);
  }

  /** Test of Read from a non-existent table. */
  @Test
  public void testReadFailureTableDoesNotExist() {
    defaultPipeline.apply(
        HCatalogIO.read()
            .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
            .withTable("myowntable"));
    thrown.expectCause(isA(NoSuchObjectException.class));
    defaultPipeline.run();
  }

  /** Test of Read without specifying configuration properties. */
  @Test
  public void testReadFailureValidationConfig() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("withConfigProperties() is required");
    HCatalogIO.read().withTable("myowntable").expand(null);
  }

  /** Test of Read without specifying a table. */
  @Test
  public void testReadFailureValidationTable() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("withTable() is required");
    HCatalogIO.read()
        .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
        .expand(null);
  }

  /** Test of Read using SourceTestUtils.readFromSource(..). */
  @Test
  @NeedsTestData
  public void testReadFromSource() throws Exception {
    ReaderContext context = getReaderContext(getConfigPropertiesAsMap(service.getHiveConf()));
    HCatalogIO.Read spec =
        HCatalogIO.read()
            .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
            .withContext(context)
            .withTable(TEST_TABLE);

    List<String> records = new ArrayList<>();
    for (int i = 0; i < context.numSplits(); i++) {
      BoundedHCatalogSource source = new BoundedHCatalogSource(spec.withSplitId(i));
      for (HCatRecord record : SourceTestUtils.readFromSource(source, OPTIONS)) {
        records.add(record.get(0).toString());
      }
    }
    assertThat(records, containsInAnyOrder(getExpectedRecords(TEST_RECORDS_COUNT).toArray()));
  }

  /** Test of Read using SourceTestUtils.assertSourcesEqualReferenceSource(..). */
  @Test
  @NeedsTestData
  public void testSourceEqualsSplits() throws Exception {
    final int numRows = 1500;
    final int numSamples = 10;
    final long bytesPerRow = 15;
    ReaderContext context = getReaderContext(getConfigPropertiesAsMap(service.getHiveConf()));
    HCatalogIO.Read spec =
        HCatalogIO.read()
            .withConfigProperties(getConfigPropertiesAsMap(service.getHiveConf()))
            .withContext(context)
            .withTable(TEST_TABLE);

    BoundedHCatalogSource source = new BoundedHCatalogSource(spec);
    List<BoundedSource<HCatRecord>> unSplitSource = source.split(-1, OPTIONS);
    assertEquals(1, unSplitSource.size());

    List<BoundedSource<HCatRecord>> splits =
        source.split(numRows * bytesPerRow / numSamples, OPTIONS);
    assertTrue(splits.size() >= 1);

    SourceTestUtils.assertSourcesEqualReferenceSource(unSplitSource.get(0), splits, OPTIONS);
  }

  private void reCreateTestTable() {
    service.executeQuery("drop table " + TEST_TABLE);
    service.executeQuery("create table " + TEST_TABLE + "(mycol1 string, mycol2 int)");
  }

  private void reCreateTestTableForUnboundedReads() throws CommandNeedRetryException {
    service.executeQuery("drop table " + TEST_TABLE);
    service.executeQuery(
        "create table "
            + TEST_TABLE
            + "(mycol1 string, mycol2 int)  "
            + "partitioned by (load_date string, product_type string)");
    service.executeQuery(
        "ALTER TABLE "
            + TEST_TABLE
            + " ADD PARTITION (load_date='2019-05-14T23:28:04.425Z', product_type='1')");
  }

  private void prepareTestData() throws Exception {
    reCreateTestTable();
    insertTestData(getConfigPropertiesAsMap(service.getHiveConf()));
  }
}
