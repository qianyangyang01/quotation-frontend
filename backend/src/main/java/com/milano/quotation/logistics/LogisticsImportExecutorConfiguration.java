package com.milano.quotation.logistics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class LogisticsImportExecutorConfiguration {
    @Bean(name="logisticsImportExecutor",destroyMethod="shutdown")
    Executor logisticsImportExecutor(@Value("${app.logistics.worker-count:1}")int workers,
                                     @Value("${app.logistics.queue-capacity:100}")int queueCapacity){
        if(workers<1||workers>2)throw new IllegalStateException("物流导入线程数必须在1到2之间");
        if(queueCapacity<1||queueCapacity>1000)throw new IllegalStateException("物流导入队列容量必须在1到1000之间");
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(workers);executor.setMaxPoolSize(workers);executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("logistics-import-worker-");executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());executor.initialize();return executor;
    }
}
