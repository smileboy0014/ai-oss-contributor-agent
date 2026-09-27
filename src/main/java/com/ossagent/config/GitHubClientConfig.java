package com.ossagent.config;

import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubCredentials;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubRateLimitBudget;
import com.ossagent.support.github.StaticTokenCredentials;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GitHub 클라이언트 조립. 비즈니스 코드는 두지 않는다.
 *
 * <p>여기서 하는 일은 <b>전송 설정</b>뿐이다 — 기준 URL 과 타임아웃 2종.
 * 프로토콜 관심사(인증 헤더 · Accept · API 버전 · 재시도 · 에러 변환)는
 * {@link GitHubApiClient} 안에 있다. 그래야 그 동작이 테스트로 고정된다.
 *
 * <p>🔴 <b>타임아웃을 기본값에 맡기지 않는다.</b> JDK {@code HttpClient} 의 기본 연결 타임아웃은
 * 「무제한」이라, 명시하지 않으면 응답 없는 대외 호출이 스레드를 영원히 잡는다 —
 * {@code .claude/rules/conventions/architecture.md} 「재시도·타임아웃은 adapter/out 에 명시」.
 */
@Configuration
@ExternalAdapter
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubClientConfig {

    @Bean
    public GitHubErrorTranslator gitHubErrorTranslator(Clock clock) {
        return new GitHubErrorTranslator(clock);
    }

    /**
     * 자격증명 공급자. 지금은 고정 classic PAT 하나지만, 갈아끼울 지점을 빈으로 열어 둔다 —
     * Q-1 「남은 것」이 {@code (#6)} 으로 지정한 요구다.
     */
    @Bean
    public GitHubCredentials gitHubCredentials(GitHubProperties properties) {
        return StaticTokenCredentials.from(properties);
    }

    /**
     * 🔴 <b>{@code RestClient} 를 빈으로 내보내지 않는다.</b> 여기서 조립해 곧바로
     * {@link GitHubApiClient} 안에 가둔다.
     *
     * <p>빈으로 노출하면 아무 컴포넌트나 주입받아 {@code restClient.post()} 를 호출할 수 있고,
     * 그 순간 「읽기 전용」은 {@code GitHubApiClient} 클래스 안에서만 참이고
     * <b>애플리케이션 컨텍스트 수준에서는 거짓</b>이 된다. S-1 을 코드 표면으로 지키기로 한 이상
     * 표면에 구멍을 내지 않는다.
     */
    @Bean
    public GitHubApiClient gitHubApiClient(GitHubProperties properties,
            GitHubCredentials credentials, GitHubErrorTranslator errorTranslator, Clock clock,
            GitHubRateLimitBudget budget) {
        return new GitHubApiClient(gitHubRestClient(properties), credentials, properties,
                errorTranslator, clock, budget);
    }

    /**
     * 레이트리밋 예산 — <b>읽기·쓰기 클라이언트가 공유한다.</b>
     *
     * <p>🔴 GitHub 레이트리밋은 <b>토큰 단위 전역</b>이다. 클라이언트마다 따로 들면 읽기 쪽이
     * 태운 예산을 쓰기 쪽이 모르고, 「임계 미만이면 호출하지 않는다」(#8)가 둘로 갈려 무력해진다.
     */
    @Bean
    public GitHubRateLimitBudget gitHubRateLimitBudget(Clock clock) {
        return new GitHubRateLimitBudget(clock);
    }

    /**
     * 🔴 <b>{@code RestClient} 를 만드는 유일한 경로.</b> 쓰기 클라이언트({@code ForkPublishConfig})도
     * 이것을 부른다.
     *
     * <p>각자 {@code RestClient.builder()} 를 부르게 두면 타임아웃·리다이렉트 정책이 <b>두 벌</b>이
     * 되고, 한쪽만 고쳐지는 날 그 사실을 아무도 모른다. {@code GitHubTransportContractTest} 가
     * 검증하는 것도 여기서 나온 클라이언트다 — 갈라지면 그 검증이 한쪽에만 유효해진다.
     *
     * <p>⚠️ 여전히 <b>빈으로 내보내지 않는다.</b> 빈이면 아무 컴포넌트나 주입받아
     * {@code restClient.post()} 를 할 수 있고, 그 순간 쓰기 표면이 {@code GitHubWriteClient} 밖으로
     * 새어 S-1 어설션을 우회한다.
     */
    static RestClient gitHubRestClient(GitHubProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    /**
     * 연결 타임아웃은 {@link HttpClient} 에, 읽기 타임아웃은 요청 팩토리에 건다.
     * 두 값이 서로 다른 곳에 걸리는 것은 JDK HTTP 클라이언트의 구조 때문이다.
     */
    static JdkClientHttpRequestFactory requestFactory(GitHubProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(properties));
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /**
     * 🔴 <b>리다이렉트를 따라가지 않는다 — S-4.</b>
     *
     * <p>리다이렉트를 켜면 클라이언트가 {@code Location} 이 가리키는 곳으로 요청을 다시 보내는데,
     * 그때 {@code Authorization} 헤더가 따라가는지는 <b>우리가 통제하지 않는 JDK 동작</b>이다.
     * 따라간다면 GitHub 이 준 주소로 토큰이 나간다. 「아마 안 따라갈 것」에 시크릿을 걸지 않는다.
     *
     * <p>대가는 작다. GitHub API 가 리다이렉트를 주는 경우는 저장소 이름 변경(301) 정도이고,
     * 그때는 <b>조용히 따라가는 것보다 드러나는 편이 낫다</b> — 등록된 좌표가 낡았다는 신호다.
     * {@code GitHubApiClient} 가 3xx 를 명시적 실패로 번역한다.
     */
    static HttpClient httpClient(GitHubProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
