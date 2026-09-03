package com.qilu.acceptance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AcceptanceFaultProperties.class)
public class AcceptanceFaultConfiguration {
}
