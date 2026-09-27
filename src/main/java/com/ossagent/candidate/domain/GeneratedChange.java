package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 가 만든 변경분.
 *
 * <p><b>독립 애그리거트 루트</b>다 — {@code ContributionCandidate} 의 멤버가 아니다.
 * 재시도할 때마다 새 행이 쌓이고 행마다 <b>수십 KB diff</b> 를 담는다. 후보 애그리거트에
 * 넣으면 후보 하나를 읽었다가 메가바이트가 딸려온다. 애그리거트는 작게 유지한다.
 *
 * <p>그래서 {@code candidateId} 는 <b>다른 애그리거트로의 ID 참조</b>이고, 이것이 정상이다.
 *
 * <p>재시도할 때마다 새 행을 남기고 <b>덮어쓰지 않는다.</b> 덮어쓰면 무엇이 어떻게
 * 바뀌었는지 추적이 사라진다.
 *
 * <p>⚠ {@code diff}·{@code testResult}·{@code reviewResult} 는 수십 KB 다.
 * 목록 조회 응답에 싣지 않는다 — 실으면 {@code GET /api/candidates} 가 메가바이트를 뱉는다.
 */
@Entity
@Table(name = "generated_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeneratedChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code contribution_candidate.id}. <b>다른 애그리거트</b>로의 ID 참조 — architecture.md 규율 ④ */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    /** {@code oss-agent/issue-{n}-{slug}} — 대상 저장소 Fork 안의 브랜치. */
    @Column(length = 512)
    private String branchName;

    @Column(length = 64)
    private String commitSha;

    /** 대상 저장소가 시크릿을 커밋해 뒀을 수 있다 — S-4. */
    @ExternalText(ExternalText.Source.TARGET_REPOSITORY)
    @Column(columnDefinition = "TEXT")
    private String diff;

    /** 빌드 로그에 토큰이 섞인다 — S-4. */
    @ExternalText(ExternalText.Source.BUILD_OUTPUT)
    @Column(columnDefinition = "TEXT")
    private String testResult;

    @ExternalText(ExternalText.Source.LLM_RESPONSE)
    @Column(columnDefinition = "TEXT")
    private String reviewResult;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * 🔴 <b>유일한 생성 경로</b> — 여기서 {@code diff} 를 스크럽한다 (S-4 · #18).
     *
     * <h2>왜 팩토리 하나만 두나</h2>
     *
     * <p>{@code ExternalTextScrubRegistryTest} 가 이 필드를 <b>「쓰는 코드가 아직 없다」</b>로
     * 두고 담당을 #18 로 지목해 뒀다. 그 「쓰는 코드」가 이것이다.
     *
     * <p>경로가 하나뿐이면 <b>스크럽을 건너뛸 수 없다.</b> Q-7 이 {@code @Builder} 를 금지한
     * 근거와 같다 — 「생성 경로가 늘면 불법 상태를 만들 수 있다」.
     *
     * <p>⚠️ <b>{@code diff} 를 받는 setter·{@code with…} 를 만들지 않는다.</b> 하나만 생겨도
     * 이 보증이 우회되고 <b>기존 테스트는 그대로 초록</b>이다 — 그 테스트는 우회 경로를 모른다.
     *
     * <h2>여기서 채우지 <b>않는</b> 것</h2>
     *
     * <p>{@code testResult} 는 <b>#19</b>, {@code reviewResult} 는 <b>#20</b> 이 채운다.
     * 생성 시점에는 아직 검증 전이므로 <b>담을 값이 없다</b> — 빈 문자열로 채우면
     * 「검증했는데 출력이 없다」와 구분되지 않는다.
     *
     * @param candidateId 다른 애그리거트로의 ID 참조
     * @param branchName  PRD §14 의 {@code oss-agent/issue-{번호}-{설명}}
     * @param diff        🔴 <b>스크럽 전 원문</b>. 이 메서드가 스크럽한다
     */
    public static GeneratedChange record(Long candidateId, String branchName, String diff,
            java.time.Clock clock) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수다");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("브랜치 이름은 필수다 — PRD §14");
        }
        if (diff == null) {
            // 🔴 빈 문자열과 null 을 가른다. 빈 diff 는 「바뀐 것이 없다」이고
            //    null 은 「못 만들었다」다 — 뭉개면 전자로 읽혀 조용히 지나간다
            throw new IllegalArgumentException("diff 는 필수다 — 변경이 없으면 빈 문자열이다");
        }
        GeneratedChange change = new GeneratedChange();
        change.candidateId = candidateId;
        change.branchName = branchName;
        change.diff = com.ossagent.support.secret.TokenRedactor.redact(diff);
        change.createdAt = java.time.Instant.now(clock);
        return change;
    }
}
