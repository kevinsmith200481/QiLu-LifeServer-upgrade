package gamer.registry;

import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;

import java.util.List;

public interface Registry {

    void init(RegistryConfig registryConfig);

    void register(ServiceMetaInfo serviceMetaInfo) throws Exception;

    void unRegister(ServiceMetaInfo serviceMetaInfo);

    List<ServiceMetaInfo> serviceDiscovery(String serviceKey);

    void heartBeat();

    /** 为完整 serviceKey 注册监听，而不是监听某个瞬时节点。 */
    void watch(String serviceKey);

    void destroy();
}
