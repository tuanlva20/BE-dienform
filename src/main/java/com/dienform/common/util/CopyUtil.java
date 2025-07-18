package com.dienform.common.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for creating deep copies of objects using serialization. Objects must implement
 * Serializable interface to be copied.
 */
@Slf4j
public class CopyUtil {

  /**
   * Functional interface for copying specific fields from an entity. Implementations should create
   * a new instance and copy only the required fields.
   *
   * @param <T> The type of entity to copy
   */
  @FunctionalInterface
  public interface EntityCopier<T> {
    T copy(T source);
  }

  /**
   * Creates a deep copy of an object using serialization. The object and all its nested objects
   * must implement Serializable.
   *
   * @param <T> The type of object to copy
   * @param object The object to copy
   * @return A deep copy of the object, or null if copying fails
   */
  @SuppressWarnings("unchecked")
  public static <T extends Serializable> T deepCopy(T object) {
    if (object == null) {
      return null;
    }

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos)) {

      // Serialize
      oos.writeObject(object);
      oos.flush();

      // Deserialize
      try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
          ObjectInputStream ois = new ObjectInputStream(bais)) {
        return (T) ois.readObject();
      }

    } catch (IOException | ClassNotFoundException e) {
      log.error("Failed to create deep copy of object: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Creates deep copies of a collection of objects using serialization. The objects and all their
   * nested objects must implement Serializable.
   *
   * @param <T> The type of objects in the collection
   * @param collection The collection to copy
   * @return A list containing deep copies of the objects, or empty list if copying fails
   */
  public static <T extends Serializable> List<T> deepCopyCollection(Collection<T> collection) {
    if (collection == null) {
      return new ArrayList<>();
    }

    List<T> copies = new ArrayList<>(collection.size());
    for (T item : collection) {
      T copy = deepCopy(item);
      if (copy != null) {
        copies.add(copy);
      }
    }
    return copies;
  }

  /**
   * Creates a detached copy of an entity by copying only specified fields. This is useful when you
   * want to copy only certain fields and avoid lazy loading issues with JPA entities.
   *
   * @param <T> The type of object to copy
   * @param object The object to copy from
   * @param copier A function that copies specific fields from the source object
   * @return A detached copy with only specified fields
   */
  public static <T> T detachedCopy(T object, EntityCopier<T> copier) {
    if (object == null || copier == null) {
      return null;
    }
    return copier.copy(object);
  }
}
