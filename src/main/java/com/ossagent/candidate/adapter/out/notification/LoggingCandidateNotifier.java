package com.ossagent.candidate.adapter.out.notification;

import com.ossagent.candidate.domain.CandidateNotification;
import com.ossagent.candidate.domain.CandidateNotifier;
import com.ossagent.support.observability.PipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 구조화 로그 + 메트릭으로 알린다 — #26 의 알림 경로 <b>첫 구현</b>.
 *
 * <h2>왜 로그와 메트릭 <b>둘 다</b>인가</h2>
 *
 * <p>하나만 두면 「알림이 조용히 아무 데도 안 간다」가 <b>드러나지 않는다.</b>
 * 로그는 「어느 후보였나」를 답하고 메트릭은 「몇 건이었나」를 답한다 —
 * 후자가 0 에 붙어 있으면 파이프라인이 도는데 후보가 안 생긴다는 뜻이고,
 * 그것은 로그를 뒤져야 알 수 있는 것이 아니라 <b>대시보드가 먼저 말해 줘야 하는 것</b>이다.
 *
 * <h2>🔴 이것이 「외부 알림」이 아니라는 사실을 숨기지 않는다</h2>
 *
 * <p>Slack·이메일·Webhook 으로 가지 않는다. 그것이 필요해지면 이 패키지에 어댑터를
 * 하나 더하고 <b>능력 인터페이스는 그대로</b> 둔다 — 갈아끼울 수 있게 domain 에 둔 이유다.
 *
 * <p>⚠️ 「이름만 있는 칸을 두지 않는다」는 규율 때문에 <b>지금 실제로 나가는 곳</b>을
 * 정확히 적는다. 「알림 경로가 있다」로만 적으면 다음 사람이 외부 전송이 있다고 믿는다.
 *
 * <h2>🔴 {@code @ExternalAdapter} 가 아니다</h2>
 *
 * <p>대외 시스템을 타지 않는다 — 로거와 미터뿐이다. 붙이면 통합 테스트에서 빠져
 * <b>알림 경로가 배선됐는지를 아무도 확인하지 않게 된다.</b> 외부 전송 어댑터가 생기면
 * <b>그것에</b> 붙는다.
 */
@Component
public class LoggingCandidateNotifier implements CandidateNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingCandidateNotifier.class);

    private final PipelineMetrics metrics;

    public LoggingCandidateNotifier(PipelineMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("메트릭은 필수다");
        }
        this.metrics = metrics;
    }

    /**
     * 🔴 <b>예외를 밖으로 내보내지 않는다</b> (FR-6) — 관찰이 대상을 죽이면 안 된다.
     *
     * <p>후보는 이미 커밋됐다. 여기서 던지면 호출자({@code AnalyzeIssuesUseCase})의
     * 배치가 죽고, <b>이미 적재된 후보가 「실패」로 보고된다.</b>
     *
     * <p>⚠️ 그렇다고 조용히 삼키지 않는다. {@code WARN} 이 남는다 — 「알림이 아무 데도
     * 안 간다」가 보이지 않으면 완료조건 2 가 이름만 채워진 칸이 된다.
     */
    @Override
    public void notifyAnalyzed(CandidateNotification notification) {
        if (notification == null) {
            return;
        }
        try {
            // 이슈 제목·본문은 여기 올 수 없다 — 값 타입에 자리가 없다 (S-4)
            log.info("새 후보 적재 candidateId={} repositoryId={} issue=#{}",
                    notification.candidateId(), notification.repositoryId(),
                    notification.issueNumber());
            metrics.candidateNotified();
        } catch (RuntimeException e) {
            log.warn("후보 알림에 실패했다 candidateId={} — 스캔은 계속한다",
                    notification.candidateId(), e);
        }
    }
}
