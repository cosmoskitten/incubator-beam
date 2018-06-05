#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys
import os.path
import re
from datetime import datetime
from bigquery_client_utils import BigQueryClientUtils


_MAX_STALE_DAYS = 360
_MAX_MINOR_VERSION_DIFF = 3
_DATE_FORMAT = "%Y-%m-%d"

def extract_results(file_path):
  """
  Extract the Java/Python dependency reports and return a collection of out-of-date dependencies.
  Args:
    file_path: the path of the raw reports
  Return:
    outdated_deps: a collection of dependencies who has updates
  """
  outdated_deps = []
  with open(file_path) as raw_report:
    see_oudated_deps = False
    for line in raw_report:
      if see_oudated_deps:
        outdated_deps.append(line)
      if line.startswith('The following dependencies have later '):
        see_oudated_deps = True
  raw_report.close()
  return outdated_deps


def extract_single_dep(dep):
  """
  Extract a single dependency check record from Java and Python reports.
  Args:
    dep: e.g "- org.assertj:assertj-core [2.5.0 -> 3.10.0]".
  Return:
    dependency name, current version, latest version.
  """
  pattern = " - ([\s\S]*)\[([\s\S]*) -> ([\s\S]*)\]"
  match = re.match(pattern, dep)
  if match is None:
    print "Failed extracting: {}".format(dep)
    return None, None, None
  return match.group(1).strip(), match.group(2).strip(), match.group(3).strip()


def prioritize_dependencies(deps, project_id, dataset_id, table_id):
  """
  Extracts and analyze dependency versions and release dates.
  Returns a collection of dependencies which is "high priority" in html format:
    1. dependency has major release. e.g org.assertj:assertj-core [2.5.0 -> 3.10.0]
    2. dependency is 3 sub-versions behind the newest one. e.g org.tukaani:xz [1.5 -> 1.8]
    3. dependency has not been updated for more than 6 months.

  Args:
    deps: A collection of outdated dependencies.
  Return:
    high_priority_deps: A collection of dependencies which need to be taken care of before next release.
  """
  high_priority_deps = []
  bigquery_client = BigQueryClientUtils(project_id, dataset_id, table_id)
  for dep in deps:
    dep_name, curr_ver, latest_ver = extract_single_dep(dep)
    curr_release_date, latest_release_date = query_dependency_release_dates(bigquery_client,
                                                                            dep_name,
                                                                            curr_ver,
                                                                            latest_ver)
    dep_info = "<tr><td>{0}</td><td>{1}</td><td>{2}</td><td>{3}</td><td>{4}</td></tr>\n".format(dep_name,
                                                                                               curr_ver,
                                                                                               latest_ver,
                                                                                               curr_release_date,
                                                                                               latest_release_date)
    if compare_dependency_versions(curr_ver, latest_ver):
      high_priority_deps.append(dep_info)
    elif compare_dependency_release_dates(curr_release_date, latest_release_date):
      high_priority_deps.append(dep_info)

  return high_priority_deps


def compare_dependency_versions(curr_ver, latest_ver):
  """
  Compare the current using version and the latest version.
  Return true if a major version change was found, or 3 minor versions that the current version is behind.
  Args:
    curr_ver
    latest_ver
  Return:
    boolean
  """
  if curr_ver is None or latest_ver is None:
    return True
  else:
    curr_ver_splitted = curr_ver.split('.')
    latest_ver_splitted = latest_ver.split('.')
    curr_major_ver = curr_ver_splitted[0]
    latest_major_ver = latest_ver_splitted[0]
    # compare major versions
    if curr_major_ver != latest_major_ver:
      return True
    # compare minor versions
    else:
      curr_minor_ver = curr_ver_splitted[1] if len(curr_ver_splitted) > 1 else None
      latest_minor_ver = latest_ver_splitted[1] if len(latest_ver_splitted) > 1 else None
      if curr_minor_ver is not None and latest_minor_ver is not None:
        if (not curr_minor_ver.isdigit() or not latest_major_ver.isdigit()) and curr_minor_ver != latest_major_ver:
          return True
        elif int(curr_minor_ver) + _MAX_MINOR_VERSION_DIFF <= int(latest_major_ver):
          return True
  return False


def query_dependency_release_dates(bigquery_client, dep_name, curr_ver, latest_ver):
  """
  Query release dates of current using version and the latest version from BQ tables.
  Args:
    bigquery_client: a bq client object that bundle configurations for API requests
    dep_name: dependency name
    curr_ver: the current using version
    latest_ver: the later version
  Return:
    curr_release_date
    latest_release_date
  """
  try:
    curr_release_date = bigquery_client.query_dep_release_date(dep_name, curr_ver)
    latest_release_date = bigquery_client.query_dep_release_date(dep_name,latest_ver)
    if curr_release_date is None:
      bigquery_client.add_dep_to_table(dep_name, curr_ver, get_date(), is_current_using=True)
    if latest_release_date is None:
      bigquery_client.add_dep_to_table(dep_name, latest_ver, get_date(), is_current_using=False)
      latest_release_date = get_date()
  except Exception, e:
    print str(e)
    return None, None
  return curr_release_date, latest_release_date


def compare_dependency_release_dates(curr_release_date, latest_release_date):
  """
  Compare release dates of current using version and the latest version.
  Return true if the current version is behind over 60 days.
  Args:
    curr_release_date
    latest_release_date
  Return:
    boolean
  """
  if curr_release_date is None or latest_release_date is None:
    return True
  else:
    if (latest_release_date - curr_release_date).days >= _MAX_STALE_DAYS:
      return True
  return False


def get_date():
  """
  Get the current date in YYYY-MM-DD
  """
  return datetime.today().date()


def generate_report(file_path, sdk_type, project_id, dataset_id, table_id):
  """
  Write SDK dependency check results into a html report.
  Args:
    file_path: the path that report will be write into.
    sdk_type: String [Java, Python, TODO: Go]
    project_id: the gcloud project ID that used for Bigquery API requests.
    dataset_id: the BigQuery dataset ID.
    table_id: the BigQuery table ID.
  """
  report_name = 'build/dependencyUpdates/beam-dependency-check-report.html'
  if os.path.exists(report_name):
    append_write = 'a'
  else:
    append_write = 'w'

  # Extract dependency check results from build/dependencyUpdate
  report = open(report_name, append_write)
  if os.path.isfile(file_path):
    outdated_deps = extract_results(file_path)
  else:
    report.write("Did not find the raw report of dependency check: {}".format(file_path))
    report.close()
    return

  # Prioritize dependencies by comparing versions and release dates.
  high_priority_deps = prioritize_dependencies(outdated_deps, project_id, dataset_id, table_id)

  # Write results to a report
  subtitle = "<h2>High Priority Dependency Updates Of Beam {} SDK:</h2>\n".format(sdk_type)
  table_fields = """<tr>
    <td><b>{0}</b></td>
    <td><b>{1}</b></td>
    <td><b>{2}</b></td>
    <td><b>{3}</b></td>
    <td><b>{4}</b></td>
    </tr>""".format("Dependency Name",
                    "Current Version",
                    "Later Version",
                    "Current Version Release Date",
                    "Later Version Release Date")
  report.write(subtitle)
  report.write("<table>\n")
  report.write(table_fields)
  for dep in high_priority_deps:
    report.write("%s" % dep)
  report.write("</table>\n")
  report.close()


def main(args):
  """
  Main method.
  Args:
    args[0]: path of the raw report generated by Java/Python dependency check. Typically in build/dependencyUpdates
    args[1]: type of the check [Java, Python]
    args[2]: google cloud project id
    args[3]: BQ dataset id
    args[4]: BQ table id
  """
  generate_report(args[0], args[1], args[2], args[3], args[4])

if __name__ == '__main__':
  main(sys.argv[1:])
