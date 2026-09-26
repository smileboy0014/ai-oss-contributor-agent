package com.ossagent.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.ossagent.agent.adapter.out.sandbox.SandboxInstanceId;
import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.support.ExternalAdapter;
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
