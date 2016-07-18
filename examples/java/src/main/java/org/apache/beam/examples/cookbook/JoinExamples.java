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
package org.apache.beam.examples.cookbook;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;

import com.google.api.services.bigquery.model.TableRow;

/**
 * This example shows how to do a join on two collections.
 * It uses a sample of the GDELT 'world event' data (http://goo.gl/OB6oin), joining the event
 * 'action' country code against a table that maps country codes to country names.
 *
 * <p>Concepts: Join operation; multiple input sources.
 *
 * <p>To execute this pipeline locally, specify general pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 * }
 * </pre>
 * and a local output file or output prefix on GCS:
 * <pre>{@code
 *   --output=[YOUR_LOCAL_FILE | gs://YOUR_OUTPUT_PREFIX]
 * }</pre>
 *
 * <p>To execute this pipeline using the Dataflow service, specify pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --tempLocation=gs://YOUR_TEMP_DIRECTORY
 *   --runner=BlockingDataflowRunner
 * }
 * </pre>
 * and an output prefix on GCS:
 * <pre>{@code
 *   --output=gs://YOUR_OUTPUT_PREFIX
 * }</pre>
 */
public class JoinExamples {

  // A 1000-row sample of the GDELT data here: gdelt-bq:full.events.
  private static final String GDELT_EVENTS_TABLE =
      "clouddataflow-readonly:samples.gdelt_sample";
  // A table that maps country codes to country names.
  private static final String COUNTRY_CODES =
      "gdelt-bq:full.crosswalk_geocountrycodetohuman";

  /**
   * Join two collections, using country code as the key.
   */
  static PCollection<String> joinEvents(PCollection<TableRow> eventsTable,
      PCollection<TableRow> countryCodes) throws Exception {

    final TupleTag<String> eventInfoTag = new TupleTag<String>();
    final TupleTag<String> countryInfoTag = new TupleTag<String>();

    // transform both input collections to tuple collections, where the keys are country
    // codes in both cases.
    PCollection<KV<String, String>> eventInfo = eventsTable.apply(
        ParDo.of(new ExtractEventDataFn()));
    PCollection<KV<String, String>> countryInfo = countryCodes.apply(
        ParDo.of(new ExtractCountryInfoFn()));

    // country code 'key' -> CGBKR (<event info>, <country name>)
    PCollection<KV<String, CoGbkResult>> kvpCollection = KeyedPCollectionTuple
        .of(eventInfoTag, eventInfo)
        .and(countryInfoTag, countryInfo)
        .apply(CoGroupByKey.<String>create());

    // Process the CoGbkResult elements generated by the CoGroupByKey transform.
    // country code 'key' -> string of <event info>, <country name>
    PCollection<KV<String, String>> finalResultCollection =
      kvpCollection.apply("Process", ParDo.of(
        new DoFn<KV<String, CoGbkResult>, KV<String, String>>() {
          @Override
          public void processElement(ProcessContext c) {
            KV<String, CoGbkResult> e = c.element();
            String countryCode = e.getKey();
            String countryName = "none";
            countryName = e.getValue().getOnly(countryInfoTag);
            for (String eventInfo : c.element().getValue().getAll(eventInfoTag)) {
              // Generate a string that combines information from both collection values
              c.output(KV.of(countryCode, "Country name: " + countryName
                      + ", Event info: " + eventInfo));
            }
          }
      }));

    // write to GCS
    PCollection<String> formattedResults = finalResultCollection
        .apply("Format", ParDo.of(new DoFn<KV<String, String>, String>() {
          @Override
          public void processElement(ProcessContext c) {
            String outputstring = "Country code: " + c.element().getKey()
                + ", " + c.element().getValue();
            c.output(outputstring);
          }
        }));
    return formattedResults;
  }

  /**
   * Examines each row (event) in the input table. Output a KV with the key the country
   * code of the event, and the value a string encoding event information.
   */
  static class ExtractEventDataFn extends DoFn<TableRow, KV<String, String>> {
    @Override
    public void processElement(ProcessContext c) {
      TableRow row = c.element();
      String countryCode = (String) row.get("ActionGeo_CountryCode");
      String sqlDate = (String) row.get("SQLDATE");
      String actor1Name = (String) row.get("Actor1Name");
      String sourceUrl = (String) row.get("SOURCEURL");
      String eventInfo = "Date: " + sqlDate + ", Actor1: " + actor1Name + ", url: " + sourceUrl;
      c.output(KV.of(countryCode, eventInfo));
    }
  }


  /**
   * Examines each row (country info) in the input table. Output a KV with the key the country
   * code, and the value the country name.
   */
  static class ExtractCountryInfoFn extends DoFn<TableRow, KV<String, String>> {
    @Override
    public void processElement(ProcessContext c) {
      TableRow row = c.element();
      String countryCode = (String) row.get("FIPSCC");
      String countryName = (String) row.get("HumanName");
      c.output(KV.of(countryCode, countryName));
    }
  }


  /**
   * Options supported by {@link JoinExamples}.
   *
   * <p>Inherits standard configuration options.
   */
  private static interface Options extends PipelineOptions {
    @Description("Path of the file to write to")
    @Validation.Required
    String getOutput();
    void setOutput(String value);
  }

  public static void main(String[] args) throws Exception {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    Pipeline p = Pipeline.create(options);
    // the following two 'applys' create multiple inputs to our pipeline, one for each
    // of our two input sources.
    PCollection<TableRow> eventsTable = p.apply(BigQueryIO.Read.from(GDELT_EVENTS_TABLE));
    PCollection<TableRow> countryCodes = p.apply(BigQueryIO.Read.from(COUNTRY_CODES));
    PCollection<String> formattedResults = joinEvents(eventsTable, countryCodes);
    formattedResults.apply(TextIO.Write.to(options.getOutput()));
    p.run();
  }

}
