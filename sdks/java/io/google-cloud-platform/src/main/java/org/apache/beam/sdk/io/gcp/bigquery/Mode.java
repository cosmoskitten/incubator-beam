/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Copied from package com.google.cloud.bigquery, see BEAM-4248.
 */
package org.apache.beam.sdk.io.gcp.bigquery;

/**
 * Mode for a BigQuery Table field. {@link Mode#NULLABLE} fields can be set to {@code null},
 * {@link Mode#REQUIRED} fields must be provided. {@link Mode#REPEATED} fields can contain more
 * than one value.
 */
enum Mode {
  NULLABLE, REQUIRED, REPEATED
}
