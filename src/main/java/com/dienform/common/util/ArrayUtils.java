package com.dienform.common.util;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArrayUtils {

  public static boolean isEmpty(List<?> list) {
    return list == null || list.isEmpty();
  }

  public static boolean isNotEmpty(List<?> list) {
    return !isEmpty(list);
  }

  public static boolean isEmpty(Map<?, ?> map) {
    return map == null || map.isEmpty();
  }

  public static boolean isNotEmpty(Map<?, ?> map) {
    return !isEmpty(map);
  }

  public static boolean isEmpty(Set<?> set) {
    return set == null || set.isEmpty();
  }

  public static boolean isNotEmpty(Set<?> set) {
    return !isEmpty(set);
  }

  public static boolean isEmpty(Collection<?> collection) {
    return collection == null || collection.isEmpty();
  }

  public static boolean isNotEmpty(Collection<?> collection) {
    return !isEmpty(collection);
  }

  public static boolean isEmpty(Object[] array) {
    return array == null || array.length == 0;
  }

  public static boolean isNotEmpty(Object[] array) {
    return !isEmpty(array);
  }
}
