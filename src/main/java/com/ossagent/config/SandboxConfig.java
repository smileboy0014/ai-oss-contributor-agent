package com.ossagent.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.ossagent.agent.adapter.out.sandbox.SandboxInstanceId;
import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.support.ExternalAdapter;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 샌드박스 조립 — #17. 비즈니스 코드를 두지 않는다.
 *
 * <p>🔴 {@code @ExternalAdapter} 다. 이 설정이 테스트 컨텍스트에 올라오면
 * <b>자동 스위트가 실제 Docker 데몬을 잡는다</b> — 그리고 샌드박스는 신뢰할 수 없는 코드를
 * 실행하는 물건이다(S-3). {@code ExternalAdapterIsolationTest} 가 그것을 잡는다.
 *
 * <p>⚠ {@code IssueScanConfig} 와 다르다. 저쪽은 대외 호출을 만들지 않는 순수 설정이라
 * 마커를 붙이지 않았다. 여기는 {@link DockerClient} 를 만든다.
 */
@Configuration
@ExternalAdapter
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    /**
     * 🔴 <b>API 버전을 명시한다</b> — Q-9b 가 운영 코드로 번진 자리.
     *
     * <p>docker-java 는 API 버전을 <b>협상하지 않는다.</b> 기본값(v1.32)으로 요청하고
     * 최신 엔진이 그것을 400 으로 거부하는데, 증상이
     * 「{@code Could not find a valid Docker environment}」라 <b>Docker 가 안 떠 있는 것처럼
     * 보인다.</b> 실제 원인은 API 버전 거부다.
     *
     * <p>{@code build.gradle.kts} 는 테스트에서 시스템 프로퍼티({@code api.version})로 이것을
     * 피해 왔다. <b>운영은 전역 시스템 프로퍼티에 기대지 않는다</b> — 다른 라이브러리와
     * 충돌하고, 무엇보다 설정이 코드 밖에 있으면 왜 그 값인지가 사라진다.
     *
     * <p>⚠ 이 값은 <b>핀이지 고정이 아니다.</b> 데몬이 이보다 낮으면 내려야 한다.
     *
     * <p>⚠ 연결을 여기서 확인하지 않는다. Docker 가 없다고 <b>기동이 막히면 안 된다</b> —
     * 샌드박스를 쓰지 않는 경로(API 조회 등)까지 죽는다. 연결 실패는 실행 시점에
     * {@code SandboxTransientException} 으로 드러난다.
     */
    @Bean
    public DockerClient dockerClient(SandboxProperties properties) {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withApiVersion(properties.dockerApiVersion())
                .build();

        // zerodep 전송은 추가 HTTP 스택을 끌고 오지 않는다. Testcontainers 도 이것을 쓴다
        ZerodepDockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 워크스페이스 루트를 준비한다 — <b>조립 단계에서 한다.</b>
     *
     * <p>🔴 설정 객체({@code SandboxProperties})가 이 일을 하지 않는 이유 — record 를
     * 만드는 것만으로 파일시스템에 쓰게 되고, 그러면 읽기전용 FS·권한 문제에서
     * <b>샌드박스를 쓰지 않는 경로까지 기동이 막힌다.</b> 위 {@link #dockerClient} 가
     * 「Docker 가 없다고 기동이 막히면 안 된다」고 한 것과 같은 원칙이다.
     *
     * <p>⚠ <b>실패해도 기동을 막지 않는다.</b> 경고만 남기고, 실제 실행 시점에
     * {@code SandboxWorkspace.under} 가 거부한다 — 그쪽이 더 정확한 자리다.
     */
    @Bean
    public ApplicationRunner sandboxWorkspaceRootInitializer(SandboxProperties properties) {
        return args -> {
            try {
                Files.createDirectories(properties.workspaceRoot());
            } catch (IOException e) {
                log.warn("워크스페이스 루트를 만들지 못했다 — 샌드박스 실행이 거부된다 root={}",
                        properties.workspaceRoot(), e);
            }
        };
    }

    /**
     * 이 인스턴스를 가리키는 식별자 — 컨테이너 라벨에 실린다.
     *
     * <p>🔴 {@code sandbox=true} 만으로는 누수 컨테이너를 정리할 주체(#26)가
     * 「남의 것을 지워도 되는가」라는 딜레마를 다시 만난다. 다중 인스턴스에서 자기 것만
     * 고를 수 있어야 한다.
     */
    @Bean
    public SandboxInstanceId sandboxInstanceId() {
        return new SandboxInstanceId(java.util.UUID.randomUUID().toString());
    }
}
