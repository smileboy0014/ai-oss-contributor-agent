package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.testing.probe.github.ProbeExternalAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 판정기가 <b>실제로 물어뜯는지</b>를 증명한다.
 *
 * <p>이것이 없으면 {@link ExternalAdapterIsolationTest} 의 「대외 어댑터 빈이 없다」는
 * <b>판정기가 항상 {@code false} 를 돌려줘도</b> 통과한다. 오늘 {@code main} 에 대외 어댑터가
 * 0개라 그 차이를 구분할 방법이 이 테스트뿐이다.
 *
 * <p>이 저장소는 「검사했다고 믿었지만 0건이었다」에 이미 한 번 당했다 — 조건부 skip 때문에
 * 5건이 조용히 건너뛰어졌고 빌드는 SUCCESS 였다({@code SchemaMigrationTest} 주석).
 */
class ExternalAdapterPackagesTest {

    @Test
    void 기술_패키지에_있는_클래스를_대외로_판정한다() {
        assertThat(ExternalAdapterPackages.isExternalAdapter(ProbeExternalAdapter.class))
                .as("판정기가 미끼(%s)를 놓쳤다 — 가드 전체가 무력화된 상태다",
                        ProbeExternalAdapter.class.getName())
                .isTrue();
    }

    @Test
    void 아직_존재하지_않는_어댑터_이름도_규칙으로_판정한다() {
        // 고정 목록이 아니라 규칙이라는 증거. #6·#22·#23 이 만들 이름을 미리 넣는다
        assertThat(List.of(
                "com.ossagent.issue.adapter.out.github.GitHubIssueSource",
                "com.ossagent.repository.adapter.out.github.GitHubRepositorySource",
                "com.ossagent.support.github.GitHubApiClient",
                "com.ossagent.agent.adapter.out.llm.ClaudeCodePlanner",
                "com.ossagent.agent.adapter.out.sandbox.DockerCodeSandbox",
                "com.ossagent.pullrequest.adapter.out.github.GitHubDraftPrPublisher"))
                .allSatisfy(name -> assertThat(ExternalAdapterPackages.isExternalAdapter(name))
                        .as("%s 를 대외 어댑터로 판정하지 못했다", name)
                        .isTrue());
    }

    @Test
    void 영속_어댑터와_도메인은_대외가_아니다() {
        // persistence 를 잡아버리면 Testcontainers 통합 테스트가 전부 깨진다 — Q-2b
        assertThat(List.of(
                "com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository",
                "com.ossagent.repository.application.RegisterRepositoryUseCase",
                "com.ossagent.issue.domain.FakeIssueSource",
                "com.ossagent.candidate.domain.ContributionCandidate",
                "com.ossagent.config.ClockConfig"))
                .allSatisfy(name -> assertThat(ExternalAdapterPackages.isExternalAdapter(name))
                        .as("%s 를 대외 어댑터로 잘못 판정했다", name)
                        .isFalse());
    }

    @Test
    void 우리_패키지_밖은_판정하지_않는다() {
        // 프레임워크·라이브러리 빈이 우연히 같은 세그먼트를 가져도 걸리지 않아야 한다
        assertThat(List.of(
                "org.kohsuke.github.GHRepository",
                "com.github.dockerjava.api.DockerClient"))
                .allSatisfy(name -> assertThat(ExternalAdapterPackages.isExternalAdapter(name))
                        .as("%s 는 com.ossagent 밖이므로 판정 대상이 아니다", name)
                        .isFalse());
    }

    @Test
    void 널과_기본패키지_클래스를_안전하게_처리한다() {
        assertThat(ExternalAdapterPackages.isExternalAdapter((String) null)).isFalse();
        assertThat(ExternalAdapterPackages.isExternalAdapter("NoPackageClass")).isFalse();
    }
}
