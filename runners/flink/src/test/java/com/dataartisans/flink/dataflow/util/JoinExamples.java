/*
 * Copyright 2015 Data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dataartisans.flink.dataflow.util;

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.options.Validation;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResult;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.transforms.join.KeyedPCollectionTuple;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.TupleTag;

/**
 * Copied from {@link com.google.cloud.dataflow.examples.JoinExamples} because the code
 * is private there.
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
	public static PCollection<String> joinEvents(PCollection<TableRow> eventsTable,
	                                      PCollection<TableRow> countryCodes) throws Exception {

		final TupleTag<String> eventInfoTag = new TupleTag<>();
		final TupleTag<String> countryInfoTag = new TupleTag<>();

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
				kvpCollection.apply(ParDo.of(new DoFn<KV<String, CoGbkResult>, KV<String, String>>() {
					@Override
					public void processElement(ProcessContext c) {
						KV<String, CoGbkResult> e = c.element();
						CoGbkResult val = e.getValue();
						String countryCode = e.getKey();
						String countryName = "none";
						countryName = e.getValue().getOnly(countryInfoTag, "Kostas");
						for (String eventInfo : c.element().getValue().getAll(eventInfoTag)) {
							// Generate a string that combines information from both collection values
							c.output(KV.of(countryCode, "Country name: " + countryName
									+ ", Event info: " + eventInfo));
						}
					}
				}));

		// write to GCS
		return finalResultCollection
				.apply(ParDo.of(new DoFn<KV<String, String>, String>() {
					@Override
					public void processElement(ProcessContext c) {
						String outputstring = "Country code: " + c.element().getKey()
								+ ", " + c.element().getValue();
						c.output(outputstring);
					}
				}));
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
	 * <p>
	 * Inherits standard configuration options.
	 */
	private interface Options extends PipelineOptions {
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
