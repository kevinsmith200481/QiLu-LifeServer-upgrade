package gamer.model;

import cn.hutool.core.util.StrUtil;
import gamer.constant.RpcConstant;
import lombok.Data;

@Data
public class ServiceMetaInfo {

    private String serviceName;

    private String serviceVersion = RpcConstant.DEFAULT_SERVICE_VERSION;

    private String serviceHost;

    private Integer servicePort;

    private String serviceGroup = "default";

    public String getServiceKey() {
        return String.format("%s:%s", serviceName, serviceVersion);
    }

    public String getServiceNodeKey() {
        return String.format("%s/%s:%s", getServiceKey(), serviceHost, servicePort);
    }

    public String getServiceAddress() {
        if (!StrUtil.contains(serviceHost, "http")) {
            return String.format("http://%s:%s", serviceHost, servicePort);
        }
        return String.format("%s:%s", serviceHost, servicePort);
    }
}
