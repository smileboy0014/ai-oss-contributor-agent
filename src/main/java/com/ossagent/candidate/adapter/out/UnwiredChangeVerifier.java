package com.ossagent.candidate.adapter.out;

import com.ossagent.candidate.domain.ChangeVerifier;
import com.ossagent.candidate.domain.VerificationReport;
import com.ossagent.candidate.domain.VerificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🔴 검증기가 배선되지 않았을 때의 <b>닫히는 기본값</b> — #18 · S-6.
 *
 * <h2>왜 이것이 필요한가</h2>
 *
 * <p>상태머신이 {@code IMPLEMENTING.allowedNext = {TESTING, FAILED}} 로 돼 있다.
 * 즉 코드 생성 뒤에 나갈 길이 <b>둘뿐</b>이고, 실제 검증기(#19)가 아직 없으면 후보가
 * {@code IMPLEMENTING} 에 <b>갇힌다.</b>
 *
 * <p>{@code #24} 가 {@code implement} 엔드포인트를 열지 않은 이유가 정확히 그것이었다 —
 * 「탈출 트리거가 없는 상태를 만들지 않는다」.
 *
 * <h2>🔴 갇히는 것과 실패하는 것은 다르다</h2>
 *
 * <table border="1">
 *   <caption>기본값이 없을 때 vs 있을 때</caption>
 *   <tr><th></th><th>결과</th></tr>
 *   <tr><td>기본값 없음</td>
 *       <td>후보가 {@code IMPLEMENTING} 에 <b>영구히 머문다.</b> 사람이 볼 신호가 없다</td></tr>
 *   <tr><td><b>이 빈</b></td>
 *       <td>{@code FAILED} — <b>종단이고, 그 자체가 사람에게 넘기는 신호</b>다 (S-6)</td></tr>
 * </table>
 *
 * <p>⚠️ <b>게이트가 느슨해지지 않는다.</b> 이 구현은 항상 <b>실패</b>를 돌려준다 —
 * 통과를 돌려주는 기본값이었다면 그것이야말로 「검증 없이 통과」이고 제품 정의가 무너진다.
 *
 * <h2>⚠️ 사유를 진짜 검증 실패와 구분되게 적는다</h2>
 *
 * <p>#21 의 에러 분석이 이 사유를 읽고 「코드를 고쳐 재시도」를 시도하면 <b>헛돈다.</b>
 * 고칠 것이 대상 저장소 코드가 아니라 <b>우리 배선</b>이기 때문이다. 그래서 사유에
 * 「검증기가 배선되지 않았다」를 명시한다.
 *
 * <h2>🔴 빈으로 만들지 않는다 — {@code @ConditionalOnMissingBean} 을 쓰지 않는 이유</h2>
 *
 * <p>그 애노테이션은 <b>자동설정 클래스 전용</b>이다. 컴포넌트 스캔으로 올라오는 빈에
 * 붙이면 평가 시점이 스캔 순서에 좌우돼 <b>조용히 어긋난다</b> — 기본값이 실물을 가리거나
 * 반대로 둘 다 올라와 주입이 모호해진다. 증상이 예외가 아니라 <b>「잘못된 쪽이 선택됨」</b>이라
 * 발견이 늦다.
 *
 * <p>대신 <b>호출자가 명시적으로 고른다</b> — {@code ObjectProvider.getIfAvailable(…)}.
 * 실물이 있으면 실물, 없으면 이것이다. 순서에 기대는 자리가 없다.
 */
public class UnwiredChangeVerifier implements ChangeVerifier {

    private static final Logger log = LoggerFactory.getLogger(UnwiredChangeVerifier.class);

    /** 🔴 #21 의 에러 분석이 「우리 배선 문제」로 읽을 수 있어야 한다. */
    static final String REASON = "검증기가 배선되지 않았다 — #19 가 들어오기 전까지 이 후보는 진행할 수 없다";

    @Override
    public VerificationReport verify(VerificationRequest request) {
        // ⚠ warn 이다. 운영에서 이 줄이 보이면 배선이 빠진 것이고, 조용히 넘기면
        //   「후보가 왜 다 FAILED 인가」를 상태 테이블에서부터 역추적하게 된다
        log.warn("검증기 미배선 — 후보를 진행시키지 않는다 candidateId={} attempt={}",
                request.candidateId(), request.attempt());
        return VerificationReport.failure(REASON);
    }
}
