package com.milano.quotation.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class WebReleaseConfigurationTest {
    @Test void webSlotHasNoScheduledProcessorButDefaultWorkerRetainsIt() {
        var runner=new ApplicationContextRunner().withUserConfiguration(BackgroundSchedulingConfiguration.class);
        runner.run(context->assertThat(context).hasSingleBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class));
        runner.withPropertyValues("app.release.web-only=true").run(context->
            assertThat(context).doesNotHaveBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class));
    }
    @Test void webSlotFailsClosedWhenAnyStartupWriterIsEnabledOrUnspecified() throws Exception {
        var keys=new String[]{"spring.flyway.enabled", "app.shimo.worker-enabled", "app.logistics.resume-on-start",
            "app.logistics.reconcile-interrupted", "app.storage.initialize"};
        var environment=new MockEnvironment();
        for(var key:keys)environment.setProperty(key,"false");
        var runner=new WebReleaseConfiguration().verifyWebReleaseIsolation(environment);
        runner.run(new DefaultApplicationArguments());
        for(var key:keys){
            environment.setProperty(key,"true");
            assertThatThrownBy(()->runner.run(new DefaultApplicationArguments())).hasMessageContaining(key);
            environment.setProperty(key,"false");
        }
        assertThatThrownBy(()->new WebReleaseConfiguration().verifyWebReleaseIsolation(new MockEnvironment())
            .run(new DefaultApplicationArguments())).hasMessageContaining("spring.flyway.enabled");
    }
}
