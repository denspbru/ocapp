package com.nicodim.ocapp.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutionConfiguration {
    @Bean(destroyMethod = "shutdownNow") ExecutorService conversionExecutor(ConverterProperties properties) {
        return Executors.newFixedThreadPool(properties.getLimits().getMaxConcurrent(), Thread.ofPlatform().name("conversion-", 0).factory());
    }
}
