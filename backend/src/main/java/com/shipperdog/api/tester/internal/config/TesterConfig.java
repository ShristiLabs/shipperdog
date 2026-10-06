package com.shipperdog.api.tester.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the tester module's settings (issue #26). */
@Configuration
@EnableConfigurationProperties(TesterProperties.class)
public class TesterConfig {}
