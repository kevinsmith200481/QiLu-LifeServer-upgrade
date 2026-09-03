package gamer.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.setting.dialect.Props;

public class ConfigUtils {

    public static <T> T loadConfig(Class<T> tClass, String prefix) {
        String activeProfile = System.getProperty("spring.profiles.active");
        if (StrUtil.isBlank(activeProfile)) {
            activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (StrUtil.isNotBlank(activeProfile)) {
            // RPC initializes before the Spring context is ready. Reading the
            // same profile flag here keeps registry files and ports isolated.
            String firstProfile = activeProfile.split(",")[0].trim();
            if (StrUtil.isNotBlank(firstProfile)) {
                return loadConfig(tClass, prefix, firstProfile);
            }
        }
        return loadConfig(tClass, prefix, "");
    }

    public static <T> T loadConfig(Class<T> tClass, String prefix, String environment) {
        StringBuilder configFileBuilder = new StringBuilder("application");
        if (StrUtil.isNotBlank(environment)) {
            configFileBuilder.append("-").append(environment);
        }
        configFileBuilder.append(".properties");
        Props props = new Props(configFileBuilder.toString());
        // RPC 早于 Spring 容器初始化：同前缀 JVM 参数作为运行时最高优先级，便于按场景安全覆盖超时等配置。
        String propertyPrefix = prefix + ".";
        for (String propertyName : System.getProperties().stringPropertyNames()) {
            if (propertyName.startsWith(propertyPrefix)) {
                props.setProperty(propertyName, System.getProperty(propertyName));
            }
        }
        return props.toBean(tClass, prefix);
    }
}
