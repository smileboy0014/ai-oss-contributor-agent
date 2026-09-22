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
}
