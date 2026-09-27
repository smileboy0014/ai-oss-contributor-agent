package com.ossagent.candidate.domain;

/**
 * 「새 후보가 생겼다」는 알림의 내용 — #26 FR-5.
 *
 * <h2>🔴 이슈 제목·본문·판정 사유를 받을 자리가 <b>타입에 없다</b> (S-4)</h2>
 *
 * <p>「나중에 필요하면 넣자」로 자리를 비워 두지 않았다. 알림은 <b>밖으로 나가는 경로</b>다 —
 * 지금은 로그·메트릭뿐이지만 이 능력이 존재하는 이유가 「나중에 Slack·Webhook 을 붙인다」이고,
 * 그때 필드가 있으면 <b>대상 저장소의 텍스트가 그대로 실려 나간다.</b>
 *
 * <p>{@code ScanPipelineResult} 가 쓰는 수법과 같다 — <b>식별자와 우리 어휘만</b> 싣고,
 * 사람이 내용을 보고 싶으면 그 식별자로 우리 API 를 조회한다.
 *
 * <p>⚠️ 필드를 더하고 싶어지면 먼저 묻는다: <b>그 값의 출처가 대상 저장소인가.</b>
 * 그렇다면 여기 들어올 수 없다 — 들어오는 순간 스크럽을 강제할 지점이 필요해지고,
 * 「호출자가 기억하는」 구조가 된다.
 *
 * @param candidateId  우리 식별자
 * @param issueNumber  대상 저장소의 이슈 번호 — <b>숫자</b>다
 * @param repositoryId 우리 식별자
 */
public record CandidateNotification(Long candidateId, Long repositoryId, int issueNumber) {

    public CandidateNotification {
        if (candidateId == null || repositoryId == null) {
            throw new IllegalArgumentException("후보·저장소 식별자는 필수다");
        }
        if (issueNumber <= 0) {
            throw new IllegalArgumentException("이슈 번호는 양수다: " + issueNumber);
        }
    }
}
