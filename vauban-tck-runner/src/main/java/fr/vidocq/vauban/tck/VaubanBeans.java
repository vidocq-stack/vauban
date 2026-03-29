package fr.vidocq.vauban.tck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.jboss.cdi.tck.spi.Beans;

public class VaubanBeans implements Beans {
    @Override
    public boolean isProxy(Object instance) {
        // Check if the instance's class name ends with _ClientProxy
        return instance != null && instance.getClass().getName().endsWith("_ClientProxy");
    }

    @Override
    public byte[] passivate(Object instance) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(instance);
        }
        return baos.toByteArray();
    }

    @Override
    public Object activate(byte[] bytes) throws IOException, ClassNotFoundException {
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
