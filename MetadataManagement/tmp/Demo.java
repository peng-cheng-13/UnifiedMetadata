import org.iq80.leveldb.*;
import static org.iq80.leveldb.impl.Iq80DBFactory.*;
import java.io.*;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;

public class Demo {
 
  private static JavaSerializer serializer = new JavaSerializer<HashSet>();
  private static DB db = null;

  private static void put(String key, HashSet<String> value) {
    db.put(bytes(key), serializer.serialize(value));
  }

  private static HashSet<String> get(String key) {
    byte[] valuebyte = db.get(bytes(key));
    HashSet rv = (HashSet)serializer.deserialize(valuebyte);
    return rv;
  }

  private static void prepareData() {
    int NumofKeys = 1000;
    int NumofValues = 10000;
    int NumofPaths = 10;
  }

  public static void main(String[] args) {
    Options options = new Options();
    options.createIfMissing(true);

    try {
      db = factory.open(new File("example"), options);

      //prepareData
      /*
      System.out.println("Prepare 10 Million KV pairs in LevelDB");
      int NumofKeys = 1000;
      int NumofValues = 10000;
      int NumofPaths = 10;
      for (int i = 0; i < NumofKeys; i++) {
        for (int j = 0; j < NumofValues; j++) {
          HashSet<String> pathset = new HashSet();
          String tmpkey = "key-" + i + "-size" + j;
          for (int k = 0; k < NumofPaths; k++) {
            long filename = i * NumofKeys + j * NumofValues + k;
            pathset.add("file-" + filename);
          }
          put(tmpkey, pathset);
        }
      }
      System.out.println("Data preparation done");  
      */
      long startTime,endTime;

      //Insert test
      System.out.println("Insert Test");
      for (int i = 0; i < 5; i++) {
        String key = "event-" + i;
        HashSet<String> pvalue = new HashSet<>();
        pvalue.add("path-1");
        pvalue.add("path-2");
        startTime = System.nanoTime();
        put(key, pvalue);
        endTime = System.nanoTime();
        System.out.println("Insert data in " + ((endTime - startTime) / 1000) + " us.");
      }
      
      //Select test
      System.out.println("Select Test");
      //fresh data
      for (int i = 0; i < 5; i++) {
        String key = "event-" + i;
        startTime = System.nanoTime();
        HashSet<String> rvalue = get(key);
        endTime = System.nanoTime();
        System.out.println("Select file in " + ((endTime - startTime) / 1000) + " us.");
        if(rvalue != null) {
          Iterator<String> iterator = rvalue.iterator();
          while (iterator.hasNext()) {
            System.out.println("Path: " + iterator.next());
          }
        }
      }
      //old data
      for (int i = 0; i < 5; i++) {
        String key = "key-" + i + "-size" + i;
        startTime = System.nanoTime();
        HashSet<String> rvalue = get(key);
        endTime = System.nanoTime();
        System.out.println("Select file in " + ((endTime - startTime) / 1000) + " us.");
        if(rvalue != null) {
          Iterator<String> iterator = rvalue.iterator();
          while (iterator.hasNext()) {
            System.out.println("Path: " + iterator.next());
          }
        }
      }

      //Delete test
      System.out.println("Delete Test");
      for (int i = 0; i < 5; i++) {
        String key = "event-" + i;
        startTime = System.nanoTime();
        db.delete(bytes(key));
        endTime = System.nanoTime();
        System.out.println("Delete file in " + ((endTime - startTime) / 1000) + " us.");
      }

      //db.delete(bytes("Tampa"), wo);
      db.close();
    } catch (IOException e) {
       System.out.println("DB failed");
    }
    System.out.println("Goodbye!");
  }

}
