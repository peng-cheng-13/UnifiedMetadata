/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.ufs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * The JavaSerializer.
 * @param <T> type of target object
 */
public class JavaSerializer<T extends Serializable> {

  /**
   * Deserialize byte array to target object.
   * @param bytes input byte array
   * @return target object
   */
  public T deserialize(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    try {
      ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
      ObjectInputStream ois = new ObjectInputStream(bais);
      return (T) ois.readObject();
    } catch (Exception e) {
      System.out.println("Deserialization failed");
    }
    return null;
  }

  /**
   * Serialize the input object to byte array.
   * @param object input object
   * @return byte array
   */
  public byte[] serialize(T object) {
    if (object == null) {
      return null;
    }
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(object);
      return baos.toByteArray();
    } catch (Exception e) {
      System.out.println("Serialization failed");
    }
    return null;
  }
}
