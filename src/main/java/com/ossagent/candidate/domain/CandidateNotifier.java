package com.ossagent.candidate.domain;

/**
 * 새 후보가 쌓였음을 알린다 — #26 FR-5 · 이슈 완료조건 2.
 *
 * <h2>🔴 알림은 <b>관찰</b>이지 행위가 아니다 (S-6)</h2>
 *
 * <p>구현이 승인 게이트({@code selectByHuman}·{@code startImplementing})를 부르는 경로를
 * 만들지 않는다. 「알림을 받았으니 자동으로 선정한다」는 순간 사람의 승인 지점이
 * 코드로 우회된다 — 이 제품의 존재 이유가 사라진다.
 *
 * <p>{@code ApprovalGateArchitectureTest} 가 이미 이것을 잡는다. 그 규칙은 「스케줄러
 * 패키지를 막는다」는 <b>거부목록이 아니라</b> 「승인 게이트는 web 어댑터만 부른다」는
 * <b>허용목록</b>이라(#73), 새 패키지가 규칙을 고치지 않고 자동으로 덮인다.
 *
 * <h2>🔴 알림 실패가 스캔을 실패시키지 않는다 (FR-6)</h2>
 *
 * <p><b>관찰이 대상을 죽이면 안 된다.</b> 후보는 이미 커밋됐고, 알림이 안 갔다고
 * 그것이 되돌아가지 않는다. 구현은 예외를 밖으로 내보내지 않는다.
 *
 * <p>⚠️ 그렇다고 <b>조용히</b> 삼키지도 않는다. 「알림이 아무 데도 안 간다」가 되면
 * 완료조건 2 가 이름만 채워진 칸이 된다 — 실패는 {@code WARN} 으로 남긴다.
 *
 * <h2>능력 이름이다</h2>
 *
 * <p>{@code Slack}·{@code Webhook} 같은 기술 이름이 domain 에 나타나지 않는다 (규율 ③).
 * 외부 전송이 필요해지면 {@code adapter/out/notification} 에 어댑터 한 장을 더한다.
 */
public interface CandidateNotifier {

    /**
     * 후보 1건이 {@code ANALYZED} 로 적재됐다.
     *
     * <p>🔴 <b>트랜잭션이 커밋된 뒤에 부른다.</b> 지금 구현은 로그·메트릭이라 I/O 가 없지만,
     * 외부 전송 어댑터가 붙는 날 그 규율이 이미 서 있어야 한다 — 대외 호출 지연이
     * DB 커넥션·락 점유로 번진다.
     */
    void notifyAnalyzed(CandidateNotification notification);
}
