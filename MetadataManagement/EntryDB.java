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

package alluxio.master.journal;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;
import static org.iq80.leveldb.impl.Iq80DBFactory.bytes;
import static org.iq80.leveldb.impl.Iq80DBFactory.asString;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.File;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Class for writing/reading KV pairs to/from EntryDB.
 */
public class EntryDB {

  public DB mEntryDB = null;

  public DB initEntryDB(String path) {
    try {
      File targetfile = new File(path);
      Options options = new Options();
      options.createIfMissing(true);
      mEntryDB = factory.open(targetfile, options);
    } catch (IOException e) {
      System.out.println("DB failed");
    }
  }

  public DB getEntryDB(String path) {
  
  }

}
