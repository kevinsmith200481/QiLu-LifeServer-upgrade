package gamer.serializer;

import gamer.spi.SpiLoader;

public class SerializerFactory {

    static {
        SpiLoader.load(Serializer.class);
    }

    private static final Serializer DEFAULT_SERIALIZER = new JdkSerializer();

    public static Serializer getInstance(String key) {
        return SpiLoader.getInstance(Serializer.class, key);
    }

}
