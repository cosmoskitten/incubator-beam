package org.apache.beam.sdk.extensions.euphoria.core.translate.coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Objects;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test targeted at {@link ClassAwareKryoCoder}.
 */
public class ClassAwareKryoCoderTest {

  @Test
  public void testCoding() throws IOException {
    ClassAwareKryoCoder<ClassToBeEncoded> coder = new ClassAwareKryoCoder<>(ClassToBeEncoded.class);
    assertEncoding(coder);
  }

  @Test
  public void testCoderSerialization() throws IOException, ClassNotFoundException {
    ClassAwareKryoCoder<ClassToBeEncoded> coder = new ClassAwareKryoCoder<>(ClassToBeEncoded.class);
    ByteArrayOutputStream outStr = new ByteArrayOutputStream();
    ObjectOutputStream oss = new ObjectOutputStream(outStr);

    oss.writeObject(coder);
    oss.flush();
    oss.close();

    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(outStr.toByteArray()));
    ClassAwareKryoCoder<ClassToBeEncoded> coderDeserialized =
        (ClassAwareKryoCoder<ClassToBeEncoded>) ois.readObject();

    assertEncoding(coderDeserialized);
  }

  private void assertEncoding(ClassAwareKryoCoder<ClassToBeEncoded> coder)
      throws IOException {
    ClassToBeEncoded originalValue = new ClassToBeEncoded("XyZ", 42, Double.NaN);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    coder.encode(originalValue, outputStream);

    byte[] buf = outputStream.toByteArray();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(buf);

    ClassToBeEncoded decodedValue = coder.decode(inputStream);

    Assert.assertNotNull(decodedValue);
    Assert.assertEquals(originalValue, decodedValue);
  }


  private static class ClassToBeEncoded {

    private String firstField;
    private Integer secondField;
    private Double thirdField;

    public ClassToBeEncoded(String firstField, Integer secondField, Double thirdField) {
      this.firstField = firstField;
      this.secondField = secondField;
      this.thirdField = thirdField;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ClassToBeEncoded that = (ClassToBeEncoded) o;
      return Objects.equals(firstField, that.firstField)
          && Objects.equals(secondField, that.secondField)
          && Objects.equals(thirdField, that.thirdField);
    }

    @Override
    public int hashCode() {

      return Objects.hash(firstField, secondField, thirdField);
    }
  }
}
