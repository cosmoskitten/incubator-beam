/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.beam.sdk.io.hadoop.inputformat.unit.tests.inputformats;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

public class MutableRecordsEmpInputFormat extends InputFormat<Text, Employee> {
  private final long numberOfRecordsInEachSplit = 3L;
  private final long numberOfSplits = 3L;

  public MutableRecordsEmpInputFormat() {}

  @Override
  public RecordReader<Text, Employee> createRecordReader(InputSplit split,
      TaskAttemptContext context) throws IOException, InterruptedException {
    return new MutableRecordsRecordReader();
  }

  @Override
  public List<InputSplit> getSplits(JobContext arg0) throws IOException, InterruptedException {
    List<InputSplit> inputSplitList = new ArrayList<InputSplit>();
    for (int i = 0; i < numberOfSplits; i++) {
      InputSplit dummyInputSplitObj = new MutableRecordsInputSplit((i * numberOfSplits),
          ((i * numberOfSplits) + numberOfRecordsInEachSplit));
      inputSplitList.add(dummyInputSplitObj);
    }
    return inputSplitList;
  }

  public class MutableRecordsInputSplit extends InputSplit implements Writable {
    private long startIndex;
    private long endIndex;

    public MutableRecordsInputSplit() {}

    public MutableRecordsInputSplit(long startIndex, long endIndex) {
      this.startIndex = startIndex;
      this.endIndex = endIndex;
    }

    /**
     *  returns number of records in each split
     */
    @Override
    public long getLength() throws IOException, InterruptedException {
      return this.endIndex - this.startIndex;
    }

    @Override
    public String[] getLocations() throws IOException, InterruptedException {
      return null;
    }


    public long getStartIndex() {
      return startIndex;
    }

    public long getEndIndex() {
      return endIndex;
    }

    @Override
    public void readFields(DataInput dataIn) throws IOException {
      startIndex = dataIn.readLong();
      endIndex = dataIn.readLong();
    }

    @Override
    public void write(DataOutput dataOut) throws IOException {
      dataOut.writeLong(startIndex);
      dataOut.writeLong(endIndex);
    }
  }

  class MutableRecordsRecordReader extends RecordReader<Text, Employee> {

    private MutableRecordsInputSplit split;
    private Text currentKey = new Text();
    private Employee currentValue = new Employee();
    private long pointer = 0L;
    private long recordsRead = 0L;

    public MutableRecordsRecordReader() {}

    @Override
    public void close() throws IOException {}

    @Override
    public Text getCurrentKey() throws IOException, InterruptedException {
      return currentKey;
    }

    @Override
    public Employee getCurrentValue() throws IOException, InterruptedException {
      return currentValue;
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
      return (float) recordsRead / split.getLength();
    }

    @Override
    public void initialize(InputSplit split, TaskAttemptContext arg1)
        throws IOException, InterruptedException {
      this.split = (MutableRecordsInputSplit) split;
      pointer = this.split.getStartIndex() - 1;
      recordsRead = 0;
      makeData();
    }

    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
      if ((recordsRead++) == split.getLength()) {
        return false;
      }
      pointer++;
      boolean hasNext = hmap.containsKey(pointer);
      if (hasNext) {
        String empData[] = hmap.get(pointer).split("_");
        currentKey.set(String.valueOf(pointer));
        currentValue.setEmpName(empData[0]);
        currentValue.setEmpAddress(empData[1]);
      }
      return hasNext;
    }

    private HashMap<Long, String> hmap = new HashMap<Long, String>();

    private void makeData() {
      hmap.put(0L, "Alex_US");
      hmap.put(1L, "John_UK");
      hmap.put(2L, "Tom_UK");
      hmap.put(3L, "Nick_UAE");
      hmap.put(4L, "Smith_IND");
      hmap.put(5L, "Taylor_US");
      hmap.put(6L, "Gray_UK");
      hmap.put(7L, "James_UAE");
      hmap.put(8L, "Jordan_IND");
    }
  }
}
