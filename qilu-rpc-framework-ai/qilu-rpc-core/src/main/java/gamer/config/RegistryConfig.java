package gamer.config;

import gamer.registry.RegistryKeys;
import lombok.Data;

@Data
public class RegistryConfig {

    private String registry = RegistryKeys.FILE;

    private String address = "http://localhost:2380";

    private String username;

    private String password;

    private Long timeout = 10000L;
}
