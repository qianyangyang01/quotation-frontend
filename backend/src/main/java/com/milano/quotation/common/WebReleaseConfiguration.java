package com.milano.quotation.common;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Refuse an incorrectly configured web-only candidate before it becomes ready. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.release.web-only", havingValue="true")
public class WebReleaseConfiguration {
    @Bean ApplicationRunner verifyWebReleaseIsolation(Environment environment) {
        return args -> {
            for(var property: new String[]{"spring.flyway.enabled", "app.shimo.worker-enabled",
                    "app.logistics.resume-on-start", "app.logistics.reconcile-interrupted", "app.storage.initialize"}) {
                if(!"false".equalsIgnoreCase(environment.getProperty(property)))
                    throw new IllegalStateException("Web-only release requires " + property + "=false");
            }
        };
    }
}
