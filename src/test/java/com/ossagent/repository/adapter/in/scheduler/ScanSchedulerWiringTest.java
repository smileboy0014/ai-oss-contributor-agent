package com.ossagent.repository.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.testing.AgentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 🔴 <b>「기본 비활성」이 실제로 배선됐는가</b> — #14 R-1.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * <p>계획 초안에는 {@code @Scheduled} 만 있고 {@code scan.schedule.enabled} 를 <b>읽는
 * 코드가 없었다.</b> 설정 파일에 {@code enabled: false} 를 적어 두면 「꺼져 있다」고 믿게
 * 되지만 스케줄러는 그대로 돈다 — <b>플래그가 장식</b>이 된다.
 *
 * <p>그 상태의 증상이 무엇인지가 핵심이다. 로컬 기동·통합 테스트가 <b>GitHub 과 LLM 을
 * 자동으로 타기 시작</b>하고, 사람이 알아차리는 것은 레이트리밋이 소진되거나 토큰
 * 청구서를 볼 때다. 조용히 비싼 실패라 테스트로 고정한다.
 *
 * <p>⚠️ 이것은 <b>비용 방어</b>이지 S-6 방어가 아니다. 자동 경로가 {@code ANALYZED} 에서
 * 멈추는 것은 이 플래그와 무관하게 성립한다 — {@code ScanPipelineUseCaseTest} 가 본다.
 */
class ScanSchedulerWiringTest {

    /** 기본값 — {@code application.yml} 이 {@code enabled: false} 다. */
    @Nested
    @AgentIntegrationTest
    @DisplayName("기본 설정")
    class Default {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("🔴 스케줄러 빈이 없다 — 켜는 것은 배포 결정이다")
        void 스케줄이_비활성이면_스케줄러_빈이_없다() {
            assertThat(context.getBeanNamesForType(ScanScheduler.class))
                    .as("이 빈이 있으면 로컬 기동·테스트가 GitHub 과 LLM 을 자동으로 탄다")
                    .isEmpty();
        }

        @Test
        @DisplayName("@EnableScheduling 자체도 켜지지 않는다")
        void 스케줄링_설정도_비활성이다() {
            // ⚠ SchedulingConfigurer 타입으로 보면 안 된다 — Boot 자동설정이
            //   observabilitySchedulingConfigurer 를 항상 올린다. @EnableScheduling 이
            //   등록하는 것은 이 후처리기이고, 그것이 유일한 정확한 신호다
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .as("스케줄러 빈만 막고 @EnableScheduling 을 켜 두면 스케줄링 풀이 이유 없이 뜬다")
                    .isFalse();
        }
    }

    /** 명시적으로 켠 경우 — 배포에서 하는 일을 재현한다. */
    @Nested
    @AgentIntegrationTest
    @ActiveProfiles("fakes")
    @TestPropertySource(properties = {
            "scan.schedule.enabled=true",
            // 테스트가 실제로 스캔을 돌리지 않도록 아주 뒤로 미룬다.
            // ⚠ 켜는 것 자체를 검증하는 테스트라, 도는 것까지 검증하지 않는다
            "scan.schedule.initial-delay=600000",
            "scan.schedule.fixed-delay=600000"
    })
    @DisplayName("명시적으로 켠 경우")
    class Enabled {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("플래그가 실제로 읽힌다 — 켜면 빈이 생긴다")
        void 켜면_스케줄러_빈이_생긴다() {
            assertThat(context.getBeanNamesForType(ScanScheduler.class))
                    .as("켜도 안 생기면 @ConditionalOnProperty 의 키가 틀린 것이다 — "
                            + "그 경우 「꺼짐」 테스트는 통과하면서 기능은 영영 죽어 있다")
                    .hasSize(1);
        }
    }
}
