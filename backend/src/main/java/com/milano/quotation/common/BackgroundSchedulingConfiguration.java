package com.milano.quotation.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Web release slots share the durable queue, but never compete with its worker. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.release.web-only", havingValue="false", matchIfMissing=true)
public class BackgroundSchedulingConfiguration {}
