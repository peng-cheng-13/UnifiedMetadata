import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class JavaSerializer<T extends Serializable> {
    
    public T deserialize(byte[] bytes) {
        if(bytes == null) {
            return null;
        }
        
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            return (T)ois.readObject();
        } catch(Exception e) {
            System.out.println("Deserialization failed");
        }
        return null;
    }
    
    public byte[] serialize(T object) {
        if(object == null) {
            return null;
        }
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(object);
            return baos.toByteArray();
        } catch(Exception e) {
		System.out.println("Serialization failed");
        }
        return null;
    }
}
