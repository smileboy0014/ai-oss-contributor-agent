package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.config.ClockConfig;
import com.ossagent.issue.adapter.out.github.GitHubIssueSource;
import com.ossagent.repository.adapter.out.github.GitHubRepositorySource;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.StaticTokenCredentials;
import com.ossagent.support.testing.probe.ProbeNetworkHolder;
import com.ossagent.support.testing.probe.adapter.out.github.ProbePackageAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 판정기가 <b>실제로 물어뜯는지</b>, 그리고 <b>물면 안 되는 것을 물지 않는지</b>를 증명한다.
 *
 * <p>이것이 없으면 {@link ExternalAdapterIsolationTest} 의 「대외 어댑터 빈이 없다」는
 * 판정기가 항상 {@code false} 를 돌려줘도 통과한다.
 *
 * <p>대상을 <b>실제 클래스</b>로 잡은 것이 중요하다. 문자열 이름만 검사하면 #6 이 만든
 * 클래스들의 실제 구조(무엇을 필드로 드는가)가 바뀌어도 알아채지 못한다.
 */
class ExternalAdaptersTest {

    // ── 신호 1: 패키지 ────────────────────────────────────────────

    @Test
    void 기술_어댑터_패키지의_클래스를_대외로_판정한다() {
        assertThat(ExternalAdapters.isExternalAdapter(ProbePackageAdapter.class))
                .as("신호 1(패키지) 미끼를 놓쳤다 — 가드가 무력화된 상태다")
                .isTrue();
    }

    @Test
    void 아직_존재하지_않는_어댑터_이름도_규칙으로_판정한다() {
        // 고정 목록이 아니라 규칙이라는 증거. #10·#22·#23 이 만들 이름을 미리 넣는다
        assertThat(List.of(
                "com.ossagent.issue.adapter.out.github.GitHubIssueSource",
                "com.ossagent.agent.adapter.out.llm.ClaudeCodePlanner",
                "com.ossagent.agent.adapter.out.sandbox.DockerCodeSandbox",
                "com.ossagent.pullrequest.adapter.out.github.GitHubDraftPrPublisher"))
                .allSatisfy(name -> assertThat(ExternalAdapters.isExternalAdapterPackage(name))
                        .as("%s 를 대외 어댑터로 판정하지 못했다", name)
                        .isTrue());
    }

    @Test
    void adapter_out_기술_의_연속된_3칸일_때만_인정한다() {
        // 「패키지 어딘가에 github 가 있으면」으로 두면 support.github 의 순수 변환기·설정값까지 잡는다
        assertThat(List.of(
                "com.ossagent.support.github.GitHubErrorTranslator",
                "com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository",
                "com.ossagent.github.something.Foo"))
                .allSatisfy(name -> assertThat(ExternalAdapters.isExternalAdapterPackage(name))
                        .as("%s 는 adapter.out.{기술} 형태가 아니므로 신호 1 에 걸리면 안 된다", name)
                        .isFalse());
    }

    // ── 신호 2: 네트워크 클라이언트 보유 ──────────────────────────

    @Test
    void 네트워크_클라이언트를_들면_패키지와_무관하게_대외다() {
        // 패키지 이름을 바꿔 가드를 통과시키는 회피를 막는 신호다
        assertThat(ExternalAdapters.isExternalAdapter(ProbeNetworkHolder.class))
                .as("신호 2(네트워크 클라이언트 보유) 미끼를 놓쳤다 — 패키지명만 바꾸면 가드가 뚫린다")
                .isTrue();
    }

    @Test
    void 전송_클라이언트는_support_에_있어도_잡힌다() {
        // GitHubApiClient 는 adapter.out 이 아니라 support.github 에 있다.
        // 신호 1 로는 안 잡히고 신호 2(RestClient 보유)로 잡혀야 한다
        assertThat(ExternalAdapters.isExternalAdapterPackage(GitHubApiClient.class.getName()))
                .as("전제 확인 — GitHubApiClient 는 신호 1 대상이 아니다")
                .isFalse();

        assertThat(ExternalAdapters.isExternalAdapter(GitHubApiClient.class))
                .as("RestClient 를 든 전송 클라이언트를 놓쳤다")
                .isTrue();
    }

    @Test
    void 전이적으로_들어도_잡힌다() {
        // GitHubIssueSource 는 RestClient 를 직접 들지 않고 GitHubApiClient 를 든다
        assertThat(ExternalAdapters.isExternalAdapter(GitHubIssueSource.class)).isTrue();
        assertThat(ExternalAdapters.isExternalAdapter(GitHubRepositorySource.class)).isTrue();
    }

    // ── 오탐 방지 — 여기가 깨지면 멀쩡한 컨텍스트가 RED 가 된다 ──

    @Test
    void 순수_변환기와_설정값은_대외가_아니다() {
        assertThat(ExternalAdapters.isExternalAdapter(GitHubErrorTranslator.class))
                .as("GitHubErrorTranslator 는 Clock 만 드는 순수 변환기다")
                .isFalse();

        assertThat(ExternalAdapters.isExternalAdapter(GitHubProperties.class))
                .as("GitHubProperties 는 설정값 record 다")
                .isFalse();

        assertThat(ExternalAdapters.isExternalAdapter(StaticTokenCredentials.class))
                .as("StaticTokenCredentials 는 토큰을 들 뿐 호출하지 않는다")
                .isFalse();
    }

    @Test
    void 영속_어댑터와_도메인은_대외가_아니다() {
        // persistence 를 잡으면 Testcontainers 통합 테스트가 전부 깨진다 — Q-2b
        assertThat(ExternalAdapters.isExternalAdapter(ClockConfig.class)).isFalse();
        assertThat(ExternalAdapters.isExternalAdapterPackage(
                "com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository")).isFalse();
    }

    @Test
    void 우리_패키지_밖은_판정하지_않는다() {
        // 프레임워크 빈이 우연히 같은 세그먼트를 갖거나 RestClient 를 들어도 걸리지 않아야 한다
        assertThat(ExternalAdapters.isExternalAdapterPackage("org.kohsuke.github.GHRepository")).isFalse();
        assertThat(ExternalAdapters.isExternalAdapter(org.springframework.web.client.RestClient.class))
                .as("스프링 자체 타입을 잡으면 컨텍스트가 통째로 RED 가 된다")
                .isFalse();
    }

    @Test
    void 널과_기본패키지_클래스를_안전하게_처리한다() {
        assertThat(ExternalAdapters.isExternalAdapterPackage(null)).isFalse();
        assertThat(ExternalAdapters.isExternalAdapterPackage("NoPackageClass")).isFalse();
        assertThat(ExternalAdapters.isExternalAdapter((Class<?>) null)).isFalse();
    }
}
