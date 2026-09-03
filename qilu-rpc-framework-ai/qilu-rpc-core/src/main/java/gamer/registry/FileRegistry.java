package gamer.registry;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class FileRegistry implements Registry {

    private static final String DEFAULT_FILE = System.getProperty("user.home") + "/.qilu-rpc/registry.properties";

    private Path registryFile;

    @Override
    public void init(RegistryConfig registryConfig) {
        String filePath = System.getProperty("qilu.rpc.registry.file", DEFAULT_FILE);
        registryFile = Paths.get(filePath);
        try {
            if (registryFile.getParent() != null) {
                Files.createDirectories(registryFile.getParent());
            }
            if (!Files.exists(registryFile)) {
                Files.createFile(registryFile);
            }
        } catch (Exception e) {
            throw new RuntimeException("初始化文件注册中心失败", e);
        }
    }

    @Override
    public synchronized void register(ServiceMetaInfo serviceMetaInfo) {
        Properties properties = readProperties();
        List<ServiceMetaInfo> serviceMetaInfos = readServiceList(properties, serviceMetaInfo.getServiceKey());
        serviceMetaInfos.removeIf(item -> item.getServiceNodeKey().equals(serviceMetaInfo.getServiceNodeKey()));
        serviceMetaInfos.add(serviceMetaInfo);
        properties.setProperty(serviceMetaInfo.getServiceKey(), JSONUtil.toJsonStr(serviceMetaInfos));
        writeProperties(properties);
    }

    @Override
    public synchronized void unRegister(ServiceMetaInfo serviceMetaInfo) {
        Properties properties = readProperties();
        List<ServiceMetaInfo> serviceMetaInfos = readServiceList(properties, serviceMetaInfo.getServiceKey());
        if (CollUtil.isNotEmpty(serviceMetaInfos)) {
            serviceMetaInfos.removeIf(item -> item.getServiceNodeKey().equals(serviceMetaInfo.getServiceNodeKey()));
            if (serviceMetaInfos.isEmpty()) {
                properties.remove(serviceMetaInfo.getServiceKey());
            } else {
                properties.setProperty(serviceMetaInfo.getServiceKey(), JSONUtil.toJsonStr(serviceMetaInfos));
            }
            writeProperties(properties);
        }
    }

    @Override
    public synchronized List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
        Properties properties = readProperties();
        return readServiceList(properties, serviceKey);
    }

    @Override
    public void heartBeat() {
    }

    @Override
    public void watch(String serviceNodeKey) {
    }

    @Override
    public void destroy() {
    }

    private Properties readProperties() {
        Properties properties = new Properties();
        if (!Files.exists(registryFile)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(registryFile)) {
            properties.load(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("读取文件注册中心失败", e);
        }
        return properties;
    }

    private List<ServiceMetaInfo> readServiceList(Properties properties, String serviceKey) {
        String value = properties.getProperty(serviceKey);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        JSONArray array = JSONUtil.parseArray(value);
        List<ServiceMetaInfo> serviceMetaInfos = new ArrayList<>();
        for (Object item : array) {
            serviceMetaInfos.add(JSONUtil.toBean(JSONUtil.parseObj(item), ServiceMetaInfo.class));
        }
        return serviceMetaInfos;
    }

    private void writeProperties(Properties properties) {
        try (OutputStream outputStream = Files.newOutputStream(registryFile)) {
            properties.store(outputStream, "qilu rpc file registry");
        } catch (Exception e) {
            throw new RuntimeException("写入文件注册中心失败", e);
        }
    }
}
