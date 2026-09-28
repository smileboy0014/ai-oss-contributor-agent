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
     * <p>{@code testResult}·{@code reviewResult}·{@code commitSha} 는 생성 시점에 <b>담을 값이
     * 없다</b> — 검증·리뷰·push 가 아직 전이다. 빈 문자열로 채우면 「검증했는데 출력이 없다」와
     * 구분되지 않는다. 각각 {@link #recordVerification} · {@link #recordReview} ·
     * {@link #markPublished} 가 <b>유일한 대입 지점</b>이다.
     *
     * <p>⚠️ 그 셋은 오랫동안 <b>아무도 부르지 않았다</b> — #19·#20 이 「채운다」고 적어 두고 각자
     * 값 타입만 만들었고, 컬럼에 앉히는 코드는 어느 이슈에도 없었다. PR 본문의 검증 절이 늘 빈 값을
     * 받았던 이유다. 지금은 {@code ImplementCandidateUseCase} 가 바퀴마다 둘을 기록한다.
     *
     * @param candidateId 다른 애그리거트로의 ID 참조
     * @param branchName  PRD §14 의 {@code oss-agent/issue-{번호}-{설명}}
     * @param diff        🔴 <b>스크럽 전 원문</b>. 이 메서드가 스크럽하되, <b>가릴 것이 있으면
     *                    기록을 거부한다</b> — 정본 패치를 변조해 저장하지 않는다 (#96)
     * @throws DiffContainsSecretException diff 에 시크릿 패턴이 있다 — 가려서 저장하면 패치가 깨진다
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
        // 🔴 스크럽은 하되 **바뀌면 거부한다** (#96 · S-4). 이 컬럼은 정본 패치라 PR 게이트가
        //    upstream 에 다시 입힌다 — 가린 채 저장하면 hunk 가 안 맞거나 구조가 깨져 후보가
        //    READY_FOR_PR 에 영구 고착된다. 가리지 않고 저장하는 것은 S-4 위반이다.
        //    그래서 「시크릿 모양이 든 diff」는 기록하지 않는다 — 사람이 볼 일이다
        String scrubbed = com.ossagent.support.secret.TokenRedactor.redact(diff);
        if (!scrubbed.equals(diff)) {
            throw new DiffContainsSecretException(candidateId);
        }
        GeneratedChange change = new GeneratedChange();
        change.candidateId = candidateId;
        change.branchName = branchName;
        change.diff = scrubbed;
        change.createdAt = java.time.Instant.now(clock);
        return change;
    }

    /**
     * 샌드박스 검증 결과를 남긴다 — <b>이 필드의 유일한 대입 지점</b>이고 여기서 스크럽한다 (S-4 · #19).
     *
     * <p>입력은 {@code StageResult.summary} 를 이어 붙인 것이라 이미 스크럽돼 있지만, 이 메서드는
     * 그것을 <b>믿지 않는다</b>. 호출자가 다른 재료를 넘겨도 컬럼에 원문이 앉지 않게 한 번 더 가린다.
     *
     * @param testResult 빌드·테스트 출력 요약. 🔴 {@code null} 은 「검증을 돌리지 않았다」이므로 거부한다
     */
    public void recordVerification(String testResult) {
        if (testResult == null) {
            throw new IllegalArgumentException("검증 결과는 필수다 — 출력이 없으면 빈 문자열이다");
        }
        this.testResult = com.ossagent.support.secret.TokenRedactor.redact(testResult);
    }

    /**
     * AI 리뷰 결과를 남긴다 — <b>이 필드의 유일한 대입 지점</b>이고 여기서 스크럽한다 (S-4 · #20).
     *
     * <p>리뷰가 diff 를 인용하면 diff 안의 시크릿이 복제된다. {@code DiffReview} 가 1차로 거르지만
     * 컬럼에 앉는 마지막 문은 여기다.
     */
    public void recordReview(String reviewResult) {
        if (reviewResult == null) {
            throw new IllegalArgumentException("리뷰 결과는 필수다 — 판정이 없으면 리뷰를 돌리지 않은 것이다");
        }
        this.reviewResult = com.ossagent.support.secret.TokenRedactor.redact(reviewResult);
    }

    /**
     * Fork 에 올라간 커밋을 기록한다 — 세 번째 승인 게이트 뒤에서만 불린다 (S-1 · S-6 · #23).
     *
     * <p>🔴 이 값은 <b>관측</b>이다. GitHub 이 돌려준 sha 를 그대로 적고 우리가 조립하지 않는다.
     * {@code null} 인 채로 남아 있는 행은 「검증은 끝났지만 아직 Fork 에 올리지 않았다」이고,
     * PR 생성기가 그것을 push 가 필요하다는 신호로 읽는다.
     */
    public void markPublished(String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("커밋 sha 는 필수다 — push 응답에서 읽는다");
        }
        this.commitSha = commitSha.trim();
    }
}
