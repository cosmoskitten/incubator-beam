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
package org.apache.beam.sdk.io.parquet;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.parquet.hadoop.ParquetFileWriter.Mode.OVERWRITE;

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import javax.annotation.Nullable;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.SeekableInputStream;

/**
 * IO to read and write Parquet files.
 *
 * <h3>Reading Parquet files</h3>
 *
 * <p>{@link ParquetIO} source returns a {@link PCollection} for
 * Parquet files. The elements in the {@link PCollection} are Avro {@link GenericRecord}.
 *
 * <p>To configure the {@link Read}, you have to provide the file patterns (from) of the
 * Parquet files and the coder.
 *
 * <p>For example:
 *
 * <pre>{@code
 *  pipeline.apply(ParquetIO.read(SCHEMA).from("/foo/bar"));
 *  ...
 * }
 * </pre>
 *
 * <p>As {@link Read} is based on {@link FileIO}, it supports any filesystem (hdfs, ...).
 *
 * <h3>Writing Parquet files</h3>
 *
 * <p>{@link ParquetIO.Sink} allows you to write a {@link PCollection} of {@link GenericRecord}
 * into a Parquet file. It can be used with the general-purpose {@link FileIO} transforms.
 *
 * <p>For example:
 *
 * <pre>{@code
 *  pipeline
 *    .apply(...) // PCollection<GenericRecord>
 *    .apply(FileIO.<GenericRecord>
 *      .write()
 *      .via(ParquetIO.sink(SCHEMA))
 *      .to("destination/path")
 * }</pre>
 *
 * <p>This IO API is considered experimental and may break or receive
 * backwards-incompatible changes in future versions of the Apache Beam SDK.
 */
@Experimental(Experimental.Kind.SOURCE_SINK)
public class ParquetIO {

  /**
   * Reads {@link GenericRecord} from a Parquet file (or multiple Parquet files matching
   * the pattern).
   */
  public static Read read(Schema schema) {
    return new AutoValue_ParquetIO_Read.Builder()
      .setSchema(schema)
      .build();
  }

  /**
   * Like {@link #read(Schema)}, but reads each file in a {@link PCollection}
   * of {@link org.apache.beam.sdk.io.FileIO.ReadableFile},
   * which allows more flexible usage.
   */
  public static ReadFiles readFiles(Schema schema) {
    return new AutoValue_ParquetIO_ReadFiles.Builder()
      .setSchema(schema)
      .build();
  }

  /**
   * Implementation of {@link #read(Schema)}.
   */
  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<GenericRecord>> {

    @Nullable
    abstract ValueProvider<String> getFilepattern();

    @Nullable
    abstract Schema getSchema();

    abstract Builder builder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setFilepattern(ValueProvider<String> filepattern);
      abstract Builder setSchema(Schema schema);

      abstract Read build();
    }

    /**
     * Reads from the given filename or filepattern.
     */
    public Read from(ValueProvider<String> filepattern) {
      return builder().setFilepattern(filepattern).build();
    }

    /** Like {@link #from(ValueProvider)}. */
    public Read from(String filepattern) {
      return from(ValueProvider.StaticValueProvider.of(filepattern));
    }

    @Override
    public PCollection<GenericRecord> expand(PBegin input) {
      checkNotNull(getFilepattern(), "Filepattern cannot be null.");

      return input
        .apply("Create filepattern", Create.ofProvider(getFilepattern(), StringUtf8Coder.of()))
        .apply(FileIO.matchAll())
        .apply(FileIO.readMatches())
        .apply(readFiles(getSchema()));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder
        .add(DisplayData.item("filePattern", getFilepattern()).withLabel("Input File Pattern"));
    }
  }

  /**
   * Implementation of {@link #readFiles(Schema)}.
   */
  @AutoValue
  public abstract static class ReadFiles extends PTransform<PCollection<FileIO.ReadableFile>,
      PCollection<GenericRecord>> {

    @Nullable
    abstract Schema getSchema();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setSchema(Schema schema);
      abstract ReadFiles build();
    }

    @Override
    public PCollection<GenericRecord> expand(PCollection<FileIO.ReadableFile> input) {
      checkNotNull(getSchema(), "Schema can not be null");
      return input.apply(ParDo.of(new ReadFn())).setCoder(AvroCoder.of(getSchema()));
    }

    static class ReadFn extends DoFn<FileIO.ReadableFile, GenericRecord> {

      @ProcessElement
      public void processElement(ProcessContext processContext) throws Exception {
        FileIO.ReadableFile file = processContext.element();

        if (!file.getMetadata().isReadSeekEfficient()) {
          String filename = file.getMetadata().resourceId().getFilename();
          throw new RuntimeException(String.format("File has to be seekable: %s", filename));
        }

        SeekableByteChannel seekableByteChannel = file.openSeekable();

        try (ParquetReader<GenericRecord> reader =
                 AvroParquetReader
                   .<GenericRecord>builder(new BeamParquetInputFile(seekableByteChannel))
                   .build()) {
          GenericRecord read;
          while ((read = reader.read()) != null) {
            processContext.output(read);
          }
        }
      }
    }

    private static class BeamParquetInputFile implements InputFile {

      private SeekableByteChannel seekableByteChannel;

      BeamParquetInputFile(SeekableByteChannel seekableByteChannel) {
        this.seekableByteChannel = seekableByteChannel;
      }

      @Override
      public long getLength() throws IOException {
        return seekableByteChannel.size();
      }

      @Override
      public SeekableInputStream newStream() {
        return new DelegatingSeekableInputStream(Channels.newInputStream(seekableByteChannel)) {

          @Override
          public long getPos() throws IOException {
            return seekableByteChannel.position();
          }

          @Override
          public void seek(long newPos) throws IOException {
            seekableByteChannel.position(newPos);
          }
        };
      }
    }
  }

  /**
   * Creates a {@link Sink} that, for use with {@link FileIO#write}.
   */
  public static Sink sink(Schema schema) {
    return new AutoValue_ParquetIO_Sink.Builder()
      .setJsonSchema(schema.toString())
      .build();
  }

  /** Implementation of {@link #sink}. */
  @AutoValue
  public abstract static class Sink implements FileIO.Sink<GenericRecord> {

    @Nullable
    abstract String getJsonSchema();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setJsonSchema(String jsonSchema);
      abstract Sink build();
    }

    @Nullable
    private transient ParquetWriter<GenericRecord> writer;

    @Override
    public void open(WritableByteChannel channel) throws IOException {
      checkNotNull(getJsonSchema(), "Schema cannot be null");

      Schema schema = new Schema.Parser().parse(getJsonSchema());

      BeamParquetOutputFile beamParquetOutputFile =
        new BeamParquetOutputFile(Channels.newOutputStream(channel));

      this.writer = AvroParquetWriter.<GenericRecord>builder(beamParquetOutputFile)
        .withSchema(schema)
        .withWriteMode(OVERWRITE)
        .build();
    }

    @Override
    public void write(GenericRecord element) throws IOException {
      checkNotNull(writer, "Writer cannot be null");
      writer.write(element);
    }

    @Override
    public void flush() throws IOException {
      writer.close();
    }

    private static class BeamParquetOutputFile implements OutputFile {

      private OutputStream outputStream;

      BeamParquetOutputFile(OutputStream outputStream) {
        this.outputStream = outputStream;
      }

      @Override
      public PositionOutputStream create(long blockSizeHint) {
        return new BeamOutputStream(outputStream);
      }

      @Override
      public PositionOutputStream createOrOverwrite(long blockSizeHint) {
        return new BeamOutputStream(outputStream);
      }

      @Override
      public boolean supportsBlockSize() {
        return false;
      }

      @Override
      public long defaultBlockSize() {
        return 0;
      }
    }

    private static class BeamOutputStream extends PositionOutputStream {
      private long position = 0;
      private OutputStream outputStream;

      private BeamOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
      }

      @Override public long getPos() throws IOException {
        return position;
      }

      @Override public void write(int b) throws IOException {
        position++;
        outputStream.write(b);
      }

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        outputStream.write(b, off, len);
        position += len;
      }

      @Override
      public void flush() throws IOException {
        outputStream.flush();
      }

      @Override
      public void close() throws IOException {
        outputStream.close();
      }
    }
  }

  /** Disallow construction of utility class. */
  private ParquetIO() {}
}
