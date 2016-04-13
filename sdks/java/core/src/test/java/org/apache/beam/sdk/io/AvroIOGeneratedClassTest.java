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
package org.apache.beam.sdk.io;

import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.runners.DirectPipeline;
import org.apache.beam.sdk.runners.DirectPipelineRunner.EvaluationResults;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for AvroIO Read and Write transforms, using classes generated from {@code user.avsc}.
 */
@RunWith(JUnit4.class)
public class AvroIOGeneratedClassTest {
  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();
  private File avroFile;

  @Before
  public void prepareAvroFileBeforeAnyTest() throws IOException {
    avroFile = tmpFolder.newFile("file.avro");
  }

  private final String schemaString =
      "{\"namespace\": \"example.avro\",\n"
    + " \"type\": \"record\",\n"
    + " \"name\": \"AvroGeneratedUser\",\n"
    + " \"fields\": [\n"
    + "     {\"name\": \"name\", \"type\": \"string\"},\n"
    + "     {\"name\": \"favorite_number\", \"type\": [\"int\", \"null\"]},\n"
    + "     {\"name\": \"favorite_color\", \"type\": [\"string\", \"null\"]}\n"
    + " ]\n"
    + "}";
  private final Schema.Parser parser = new Schema.Parser();
  private final Schema schema = parser.parse(schemaString);

  private AvroGeneratedUser[] generateAvroObjects() {
    AvroGeneratedUser user1 = new AvroGeneratedUser();
    user1.setName("Bob");
    user1.setFavoriteNumber(256);

    AvroGeneratedUser user2 = new AvroGeneratedUser();
    user2.setName("Alice");
    user2.setFavoriteNumber(128);

    AvroGeneratedUser user3 = new AvroGeneratedUser();
    user3.setName("Ted");
    user3.setFavoriteColor("white");

    return new AvroGeneratedUser[] { user1, user2, user3 };
  }

  private GenericRecord[] generateAvroGenericRecords() {
    GenericRecord user1 = new GenericData.Record(schema);
    user1.put("name", "Bob");
    user1.put("favorite_number", 256);

    GenericRecord user2 = new GenericData.Record(schema);
    user2.put("name", "Alice");
    user2.put("favorite_number", 128);

    GenericRecord user3 = new GenericData.Record(schema);
    user3.put("name", "Ted");
    user3.put("favorite_color", "white");

    return new GenericRecord[] { user1, user2, user3 };
  }

  private void generateAvroFile(AvroGeneratedUser[] elements) throws IOException {
    DatumWriter<AvroGeneratedUser> userDatumWriter =
        new SpecificDatumWriter<>(AvroGeneratedUser.class);
    try (DataFileWriter<AvroGeneratedUser> dataFileWriter = new DataFileWriter<>(userDatumWriter)) {
      dataFileWriter.create(elements[0].getSchema(), avroFile);
      for (AvroGeneratedUser user : elements) {
        dataFileWriter.append(user);
      }
    }
  }

  private List<AvroGeneratedUser> readAvroFile() throws IOException {
    DatumReader<AvroGeneratedUser> userDatumReader =
        new SpecificDatumReader<>(AvroGeneratedUser.class);
    List<AvroGeneratedUser> users = new ArrayList<>();
    try (DataFileReader<AvroGeneratedUser> dataFileReader =
        new DataFileReader<>(avroFile, userDatumReader)) {
      while (dataFileReader.hasNext()) {
        users.add(dataFileReader.next());
      }
    }
    return users;
  }

  <T> void runTestRead(AvroIO.Read.Bound<T> read, String expectedName, T[] expectedOutput)
      throws Exception {
    generateAvroFile(generateAvroObjects());

    DirectPipeline p = DirectPipeline.createForTest();
    PCollection<T> output = p.apply(read);
    EvaluationResults results = p.run();
    assertEquals(expectedName, output.getName());
    assertThat(results.getPCollection(output),
               containsInAnyOrder(expectedOutput));
  }

  @Test
  public void testReadFromGeneratedClass() throws Exception {
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(AvroGeneratedUser.class),
        "AvroIO.Read/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.withSchema(AvroGeneratedUser.class).from(avroFile.getPath()),
        "AvroIO.Read/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.named("MyRead").from(avroFile.getPath()).withSchema(AvroGeneratedUser.class),
        "MyRead/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.named("MyRead").withSchema(AvroGeneratedUser.class).from(avroFile.getPath()),
        "MyRead/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(AvroGeneratedUser.class).named("HerRead"),
        "HerRead/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).named("HerRead").withSchema(AvroGeneratedUser.class),
        "HerRead/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.withSchema(AvroGeneratedUser.class).named("HerRead").from(avroFile.getPath()),
        "HerRead/Read.out",
        generateAvroObjects());
    runTestRead(
        AvroIO.Read.withSchema(AvroGeneratedUser.class).from(avroFile.getPath()).named("HerRead"),
        "HerRead/Read.out",
        generateAvroObjects());
  }

  @Test
  public void testReadFromSchema() throws Exception {
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(schema),
        "AvroIO.Read/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schema).from(avroFile.getPath()),
        "AvroIO.Read/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.named("MyRead").from(avroFile.getPath()).withSchema(schema),
        "MyRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.named("MyRead").withSchema(schema).from(avroFile.getPath()),
        "MyRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(schema).named("HerRead"),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).named("HerRead").withSchema(schema),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schema).named("HerRead").from(avroFile.getPath()),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schema).from(avroFile.getPath()).named("HerRead"),
        "HerRead/Read.out",
        generateAvroGenericRecords());
  }

  @Test
  public void testReadFromSchemaString() throws Exception {
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(schemaString),
        "AvroIO.Read/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schemaString).from(avroFile.getPath()),
        "AvroIO.Read/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.named("MyRead").from(avroFile.getPath()).withSchema(schemaString),
        "MyRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.named("MyRead").withSchema(schemaString).from(avroFile.getPath()),
        "MyRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).withSchema(schemaString).named("HerRead"),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.from(avroFile.getPath()).named("HerRead").withSchema(schemaString),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schemaString).named("HerRead").from(avroFile.getPath()),
        "HerRead/Read.out",
        generateAvroGenericRecords());
    runTestRead(
        AvroIO.Read.withSchema(schemaString).from(avroFile.getPath()).named("HerRead"),
        "HerRead/Read.out",
        generateAvroGenericRecords());
  }

  <T> void runTestWrite(AvroIO.Write.Bound<T> write, String expectedName)
      throws Exception {
    AvroGeneratedUser[] users = generateAvroObjects();

    DirectPipeline p = DirectPipeline.createForTest();
    @SuppressWarnings("unchecked")
    PCollection<T> input = p.apply(Create.of(Arrays.asList((T[]) users))
                            .withCoder((Coder<T>) AvroCoder.of(AvroGeneratedUser.class)));
    input.apply(write.withoutSharding());
    p.run();
    assertEquals(expectedName, write.getName());

    assertThat(readAvroFile(), containsInAnyOrder(users));
  }

  @Test
  public void testWriteFromGeneratedClass() throws Exception {
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(AvroGeneratedUser.class),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.withSchema(AvroGeneratedUser.class)
                             .to(avroFile.getPath()),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .to(avroFile.getPath())
                             .withSchema(AvroGeneratedUser.class),
                 "MyWrite");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .withSchema(AvroGeneratedUser.class)
                             .to(avroFile.getPath()),
                 "MyWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(AvroGeneratedUser.class)
                             .named("HerWrite"),
                 "HerWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .named("HerWrite")
                             .withSchema(AvroGeneratedUser.class),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(AvroGeneratedUser.class)
                             .named("HerWrite")
                             .to(avroFile.getPath()),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(AvroGeneratedUser.class)
                             .to(avroFile.getPath())
                             .named("HerWrite"),
                 "HerWrite");
  }

  @Test
  public void testWriteFromSchema() throws Exception {
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(schema),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.withSchema(schema)
                             .to(avroFile.getPath()),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .to(avroFile.getPath())
                             .withSchema(schema),
                 "MyWrite");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .withSchema(schema)
                             .to(avroFile.getPath()),
                 "MyWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(schema)
                             .named("HerWrite"),
                 "HerWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .named("HerWrite")
                             .withSchema(schema),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(schema)
                             .named("HerWrite")
                             .to(avroFile.getPath()),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(schema)
                             .to(avroFile.getPath())
                             .named("HerWrite"),
                 "HerWrite");
  }

  @Test
  public void testWriteFromSchemaString() throws Exception {
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(schemaString),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.withSchema(schemaString)
                             .to(avroFile.getPath()),
                 "AvroIO.Write");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .to(avroFile.getPath())
                             .withSchema(schemaString),
                 "MyWrite");
    runTestWrite(AvroIO.Write.named("MyWrite")
                             .withSchema(schemaString)
                             .to(avroFile.getPath()),
                 "MyWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .withSchema(schemaString)
                             .named("HerWrite"),
                 "HerWrite");
    runTestWrite(AvroIO.Write.to(avroFile.getPath())
                             .named("HerWrite")
                             .withSchema(schemaString),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(schemaString)
                             .named("HerWrite")
                             .to(avroFile.getPath()),
                 "HerWrite");
    runTestWrite(AvroIO.Write.withSchema(schemaString)
                             .to(avroFile.getPath())
                             .named("HerWrite"),
                 "HerWrite");
  }

  // TODO: for Write only, test withSuffix, withNumShards,
  // withShardNameTemplate and withoutSharding.
}
