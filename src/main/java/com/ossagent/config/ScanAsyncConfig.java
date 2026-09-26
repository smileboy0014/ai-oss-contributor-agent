package com.ossagent.config;

import com.ossagent.repository.application.ScanExecutor;
import com.ossagent.repository.application.ScanProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 스캔 전용 비동기 풀 — #14 NFR-1 · NFR-2.
 *
 * <h2>🔴 전용 풀을 쓰는 이유</h2>
 *
 * <p>Spring 기본 실행기({@code SimpleAsyncTaskExecutor})는 <b>요청마다 스레드를 새로
 * 만들고 상한이 없다.</b> 스캔이 GitHub 레이트리밋과 LLM 토큰을 쓰므로, 동시에 여러 개가
 * 뜨면 예산을 한 번에 태우고 서로의 리밋을 깎는다.
 *
 * <p>{@code corePoolSize = maxPoolSize = 1} 로 <b>동시 1건</b>을 강제한다.
 * Q-3(프로필 미분리)이 전제하는 단일 프로세스와 짝이다 — 동시 실행이 2건 이상 필요해지는
 * 순간이 Q-3 의 판단 기준 ①이 충족되는 때다.
 *
 * <p>🔴 <b>큐를 무한으로 두지 않는다.</b> 무한 큐는 「쌓이는 줄 모르는」 상태를 만들고,
 * 그때 쌓이는 것은 <b>몇 시간 뒤에 실행될 스캔</b>이다. 차면 {@code AbortPolicy} 로
 * 거절하고 호출자가 409 로 돌려준다 — 거절은 보이지만 누적은 보이지 않는다.
 *
 * <p>⚠️ {@code @EnableAsync} 를 여기 둔다. Q-3 을 닫을 때 이 설정에
 * {@code @Profile("worker")} 가 붙는다.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(ScanProperties.class)
public class ScanAsyncConfig {

    @Bean(ScanExecutor.EXECUTOR_BEAN)
    public Executor scanTaskExecutor(ScanProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("scan-");
        // 🔴 큐가 차면 거절한다. CallerRunsPolicy 로 두면 요청 스레드가 파이프라인을
        //    직접 돌아 NFR-1(API 스레드를 점유하지 않는다)이 무너진다
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 기동 중 종료 시 진행 중 스캔을 기다린다 — 중간에 끊기면 커서가 어중간해진다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownGraceSeconds());
        executor.initialize();
        return executor;
    }
}
