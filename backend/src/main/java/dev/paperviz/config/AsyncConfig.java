package dev.paperviz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Pipeline work pool.
     *
     * Small on purpose: each task holds a PDF in memory and occupies GROBID,
     * and later stages will occupy Manim and the LLM. Queueing is the right
     * behaviour here, not fanning out and thrashing a machine that is already
     * running a language model.
     */
    @Bean("paperVizExecutor")
    public Executor paperVizExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("paperviz-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
