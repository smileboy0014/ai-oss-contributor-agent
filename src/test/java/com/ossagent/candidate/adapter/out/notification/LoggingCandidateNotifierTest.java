package com.ossagent.candidate.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.ossagent.candidate.domain.CandidateNotification;
import com.ossagent.support.observability.MetricNames;
import com.ossagent.support.observability.PipelineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알림 구현 — #26 FR-5·FR-6.
 *
 * <p>🔴 이 클래스가 지키는 것은 <b>「관찰이 대상을 죽이지 않는다」</b>다. 후보는 이미
 * 커밋됐고, 알림이 안 갔다고 그것이 되돌아가지 않는다.
 */
class LoggingCandidateNotifierTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final LoggingCandidateNotifier notifier =
            new LoggingCandidateNotifier(new PipelineMetrics(registry));

    @Test
    @DisplayName("알림이 메트릭을 올린다 — 로그만 두면 「발화하는가」를 아무도 모른다")
    void 알림이_메트릭을_올린다() {
        notifier.notifyAnalyzed(new CandidateNotification(1L, 2L, 42));

        assertThat(registry.get(MetricNames.CANDIDATE_NOTIFIED).counter().count())
                .as("이 값이 0 에 붙어 있으면 완료조건 2 가 이름만 채워진 칸이라는 뜻이다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔴 메트릭이 터져도 전파되지 않는다 — FR-6")
    void 메트릭_실패가_전파되지_않는다() {
        PipelineMetrics failing = mock(PipelineMetrics.class);
        doThrow(new IllegalStateException("미터 레지스트리 고장")).when(failing).candidateNotified();

        assertThatCode(() -> new LoggingCandidateNotifier(failing)
                .notifyAnalyzed(new CandidateNotification(1L, 2L, 42)))
                .as("🔴 여기서 던지면 이미 적재된 후보가 「실패」로 보고된다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("메트릭 없이 만들 수 없다 — 「로그만 나가는」 구현이 조용히 생기지 않게")
    void 메트릭은_필수다() {
        assertThatThrownBy(() -> new LoggingCandidateNotifier(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
