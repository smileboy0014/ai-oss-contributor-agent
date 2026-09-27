package com.ossagent.config;

import com.ossagent.pullrequest.adapter.out.github.ForkPublishProperties;
import com.ossagent.pullrequest.adapter.out.github.GitHubDraftPrPublisher;
import com.ossagent.pullrequest.adapter.out.github.GitHubForkPublisher;
import com.ossagent.pullrequest.adapter.out.github.GitHubWriteClient;
import com.ossagent.pullrequest.domain.DraftPrPublisher;
import com.ossagent.pullrequest.domain.ForkPublisher;
import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubCredentials;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubRateLimitBudget;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fork 쓰기 조립 — S-1. 비즈니스 코드는 두지 않는다.
 *
 * <p>🔴 <b>{@code @ExternalAdapter} 가 여기 붙어야 효과가 있다.</b> 그 애노테이션은
 * {@code @Profile("!fakes")} 이므로 <b>빈 정의</b>에 걸려야 하고, {@link GitHubWriteClient} 를
 * {@code @Bean} 으로 만드는 이상 그 클래스의 애노테이션은 읽히지 않는다.
 * {@code GitHubClientConfig} 가 같은 구조다.
 *
 * <p>빠뜨리면 {@code fakes} 프로필에서도 실제 쓰기 클라이언트가 올라와
 * {@code ExternalAdapterIsolationTest} 가 <b>통합 테스트 전부를 적색</b>으로 만든다 —
 * 판정 신호 2(「필드로 네트워크 클라이언트를 보유」)에 걸리기 때문이다.
 *
 * <p>⚠️ 전송 설정을 여기서 다시 만들지 않는다. {@link GitHubClientConfig#gitHubRestClient} 를
 * 부른다 — 두 벌이 되면 타임아웃·리다이렉트 정책이 갈라지고 전송 계약 테스트가 한쪽에만 유효해진다.
 */
@Configuration
@ExternalAdapter
@EnableConfigurationProperties(ForkPublishProperties.class)
public class ForkPublishConfig {

    /**
     * 🔴 쓰기 표면은 이 빈 하나다. {@code RestClient} 를 여기서 조립해 곧바로 가둔다 —
     * 빈으로 내보내면 아무 컴포넌트나 {@code post()} 를 할 수 있고 S-1 어설션이 우회된다.
     */
    @Bean
    public GitHubWriteClient gitHubWriteClient(GitHubProperties properties,
            ForkPublishProperties forkProperties, GitHubCredentials credentials,
            GitHubErrorTranslator errorTranslator, GitHubRateLimitBudget budget, Clock clock) {
        return new GitHubWriteClient(GitHubClientConfig.gitHubRestClient(properties), credentials,
                properties, errorTranslator, budget, forkProperties.owner(), clock);
    }

    /**
     * ✅ <b>#23 이 호출자를 붙였다.</b> 원래 이 빈에는 호출자가 없었고, 그것이 의도였다 —
     * 배선이 <b>세 번째 승인 게이트 뒤</b>에 와야 했기 때문이다.
     *
     * <p>지금 유일한 호출자는 {@code CreateDraftPrUseCase} 이고 그것은
     * {@code POST /api/candidates/{id}/pull-request} 뒤에 있다. 스케줄러나
     * {@code implement} 경로에서 부르면 그 시점에 S-6 위반이고,
     * {@code ApprovalGateArchitectureTest} 가 「승인 UseCase 를 web 어댑터만 부른다」를
     * 허용목록으로 고정한다.
     */
    @Bean
    public ForkPublisher forkPublisher(GitHubApiClient readClient, GitHubWriteClient writeClient,
            ForkPublishProperties forkProperties, Clock clock) {
        return new GitHubForkPublisher(readClient, writeClient, forkProperties, clock);
    }

    /**
     * 🔴 <b>Draft PR 생성 — 이 제품이 대상 저장소에 남기는 유일한 글</b> (S-2).
     *
     * <p>{@link GitHubWriteClient} 를 공유한다. 별도 클라이언트를 만들면
     * {@code ForkPublishArchitectureTest.쓰기_표면은_한_곳이다_S1} 이 빨개지고, 그것이
     * 빨개지는 것이 옳다 — 쓰기 표면이 늘면 owner 어설션을 거치지 않는 경로가 생긴다.
     */
    @Bean
    public DraftPrPublisher draftPrPublisher(GitHubApiClient readClient,
            GitHubWriteClient writeClient) {
        return new GitHubDraftPrPublisher(readClient, writeClient);
    }
}
