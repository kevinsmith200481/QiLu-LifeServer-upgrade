package gamer.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalRegistry {

    private static final Map<String, Object> map = new ConcurrentHashMap<>();

    public static void register(String serviceName, Class<?> implClass) {
        map.put(serviceName, implClass);
    }

    public static void registerInstance(String serviceName, Object implInstance) {
        map.put(serviceName, implInstance);
    }

    public static Class<?> get(String serviceName) {
        Object service = map.get(serviceName);
        if (service instanceof Class<?>) {
            return (Class<?>) service;
        }
        return service == null ? null : service.getClass();
    }

    public static Object getService(String serviceName) {
        Object service = map.get(serviceName);
        if (service instanceof Class<?>) {
            try {
                return ((Class<?>) service).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("Create local RPC service instance failed: " + serviceName, e);
            }
        }
        return service;
    }

    public static void remove(String serviceName) {
        map.remove(serviceName);
    }
}
