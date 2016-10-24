
package cz.seznam.euphoria.core.client.io;

import java.io.Closeable;
import java.io.IOException;

/**
 * Writer for data to a particular partition.
 */
public interface Writer<T> extends Closeable {

  /** Write element to the output. */
  void write(T elem) throws IOException;


  /**
   * Flush all pending writes to output.
   * This method might be called multiple times, but is always called
   * just before {@code commit} or {@code rollback}.
   **/
  default void flush() throws IOException {

  }

  /** Commit the write process. */
  void commit() throws IOException;

  /** Rollback the write process. Optional operation. */
  default void rollback() throws IOException {}

  /**
   * Close the writer and release all its resources.
   * This method will be called as the last method on this object.
   **/
  @Override
  void close() throws IOException;
  
}
