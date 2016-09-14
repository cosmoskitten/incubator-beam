
package cz.seznam.euphoria.core.client.operator.state;

/**
 * State storage storing lists.
 */
public interface ListStateStorage<T> extends StateStorage<T> {

  /** Add element to the state. */
  void add(T element);

  /** List all elements. */
  Iterable<T> get();

  /** Add all elements. */
  default void addAll(Iterable<T> what) {
    for (T e : what) {
      add(e);
    }
  }

}
