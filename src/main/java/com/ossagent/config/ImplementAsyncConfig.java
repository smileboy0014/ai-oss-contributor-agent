package com.ossagent.config;

import com.ossagent.candidate.application.ExecutionProperties;
import com.ossagent.candidate.application.ImplementExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 착수 실행 풀 — #106. {@code ScanAsyncConfig} 와 같은 모양이다.
 *
 * <p>🔴 {@code CallerRunsPolicy} 를 쓰지 않는다 — 큐가 차면 요청 스레드가 루프를 직접 돌아
 * 「API 스레드를 점유하지 않는다」가 무너진다. 거절은 409 이고 후보는 {@code SELECTED} 로 되돌아간다.
 *
 * <p>⚠️ 종료 시 진행 중 착수를 기다린다 — 중간에 끊기면 워크스페이스·컨테이너가 어중간하게 남는다.
 * 한 바퀴가 최대 30분이라 상한을 넉넉히 둔다.
 */
@Configuration
public class ImplementAsyncConfig {

    @Bean(ImplementExecutor.EXECUTOR_BEAN)
    public Executor implementTaskExecutor(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrent());
        executor.setMaxPoolSize(properties.maxConcurrent());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("implement-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.timeoutSeconds());
        executor.initialize();
        return executor;
    }
}
