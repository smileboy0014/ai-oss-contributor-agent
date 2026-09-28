package com.ossagent.candidate.domain;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.SandboxException;
import com.ossagent.agent.domain.WorkspaceException;
import java.util.Optional;

/**
 * 한 바퀴의 결과를 {@link RetryDecision} 으로 옮기는 <b>순수 판정</b> — #21 · FR-4.
 *
 * <h2>🔴 재시도는 화이트리스트다 — 거부목록이 아니다</h2>
 *
 * <p>이슈 코멘트가 「{@code switch} 를 쓰지 마라」고 한 것의 <b>진짜 요구</b>는
 * 「새 실패 종류가 생겼을 때 <b>어디로 떨어지는가</b>」다. {@code switch} 를 피하고도
 * 거부목록으로 짜면 같은 구멍이 남는다.
 *
 * <table border="1">
 *   <caption>새 값이 생겼을 때</caption>
 *   <tr><th>축</th><th>떨어지는 곳</th><th>대가</th></tr>
 *   <tr><td>거부목록 「이것들은 재시도 안 함」</td><td>🔴 <b>재시도</b></td>
 *       <td>LLM 과금 ×3 · 샌드박스 90분</td></tr>
 *   <tr><td><b>화이트리스트 「이것들만 재시도」</b></td><td>✅ {@link RetryDecision.Stop}</td>
 *       <td>사람이 한 번 본다</td></tr>
 * </table>
 *
 * <p>방향은 {@code external-deps.md} 의 기준으로 정했다 — <b>「실패의 방향이 되돌릴 수
 * 있는가」</b>. 잘못 멈추면 사람이 재분석하면 되고, 잘못 재시도하면 <b>돈과 시간이 나간다.</b>
 *
 * <p>⚠️ 이것은 {@code testing-philosophy.md} 의 「거부목록으로 방어하지 않는다 —
 * 여집합으로 뒤집는다」와 같은 수법이다. 「무엇이 재시도 불가인가」를 세는 대신
 * <b>「무엇이 재시도 가능인가」</b>를 센다.
 *
 * <h2>🔴 통과 판정을 여집합으로 쓰지 않는다</h2>
 *
 * <p>{@code report.passed()} 와 {@code review.passed()} 만 본다.
 * {@code !failed} 로 쓰면 {@code UNDETERMINED} 가 <b>조용히 통과로 접힌다</b> —
 * {@link StageOutcome} · {@link ReviewVerdict} 가 그 값을 따로 둔 이유 전부가
 * 그 한 줄에서 사라진다.
 *
 * <h2>상한은 여기서 보지 않는다</h2>
 *
 * <p>「더 돌 수 있는가」는 {@code ContributionCandidate.retryImplementation} 이
 * {@code attempt} 로 판정한다 — 카운터의 주인이 후보 루트다(불변식 ⑧).
 * 여기는 <b>「재시도가 의미 있는가」</b>만 본다. 둘을 합치면 도메인 상수가 두 군데가 된다.
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * 검증 결과를 판정한다.
     *
     * @param report 🔴 {@code null} 금지 — 「검증을 안 돌렸다」는 이 메서드의 입력이 아니다
     */
    public static RetryDecision after(VerificationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("검증 보고서는 필수다");
        }
        // 🔴 passed() 로만 본다. noneMatch(FAILED) 로 쓰면 UNDETERMINED 가 통과로 접힌다
        if (report.passed()) {
            return new RetryDecision.Proceed();
        }
        // 🔴 판정 불가가 먼저다. 같은 입력에 같은 결과라 재시도가 예산만 태운다 —
        //    규약에서 테스트 명령을 읽지 못한 것은 다시 돌린다고 생기지 않는다
        if (report.hasUndetermined()) {
            return new RetryDecision.Stop(AgentRun.Stage.VERIFY, undeterminedReason(report));
        }
        // 여기까지 왔으면 「돌았고 실패했다」 — 고칠 대상이 있다
        return report.firstNotPassed()
                .map(stage -> (RetryDecision) new RetryDecision.Retry(CodingFeedback.of(stage)))
                // ⚠ passed() 가 거짓인데 firstNotPassed() 가 비는 것은 VerificationReport 의
                //   불변식이 깨진 것이다. 재시도로 덮지 않는다 — 사람이 봐야 한다
                .orElseGet(() -> new RetryDecision.Stop(AgentRun.Stage.VERIFY, "검증 보고서가 일관되지 않다"));
    }

    /**
     * 리뷰 결과를 판정한다. <b>검증이 통과한 뒤에만</b> 부른다.
     *
     * <p>🔴 {@code warrantsRetry()} 가 <b>{@code CHANGES_REQUESTED} 에만</b> 참이다.
     * {@code UNDETERMINED} 는 거짓이라 아래 {@code Stop} 으로 떨어진다 — 그것이
     * enum 에 판정을 둔 이유다({@link ReviewVerdict#warrantsRetry()} javadoc).
     */
    public static RetryDecision after(DiffReview review) {
        if (review == null) {
            throw new IllegalArgumentException("리뷰 결과는 필수다");
        }
        if (review.passed()) {
            return new RetryDecision.Proceed();
        }
        if (review.verdict().warrantsRetry()) {
            return new RetryDecision.Retry(CodingFeedback.of(review));
        }
        // 🔴 화이트리스트의 정의 — 위 둘이 아니면 전부 여기다.
        //    ReviewVerdict 에 값이 추가돼도 재시도로 떨어지지 않는다
        return new RetryDecision.Stop(AgentRun.Stage.REVIEW,
                "AI 리뷰 판정 불가 (" + review.verdict() + ")");
    }

    /**
     * 바퀴가 <b>예외로</b> 끝났을 때 판정한다.
     *
     * <p>🔴 <b>전부 {@link RetryDecision.Stop} 이다.</b> 「재시도가 의미 있는 예외」가
     * 지금은 하나도 없다 — 목록이 아니라 <b>근거</b>로 그렇다.
     *
     * <table border="1">
     *   <caption>왜 전부 멈추나</caption>
     *   <tr><th>예외</th><th>근거</th></tr>
     *   <tr><td>{@link CodingOutOfPlanException}</td>
     *       <td>같은 계획·같은 프롬프트면 같은 결과다 (#18 이 이미 그렇게 판정했다)</td></tr>
     *   <tr><td>{@link VerificationSetupException}</td>
     *       <td>검증을 <b>시작조차 못 했다</b>. 명령이 없거나 빌드 도구를 모른다</td></tr>
     *   <tr><td>{@link DiffReviewRejectedException}</td>
     *       <td>{@code Reason.retryable()} 이 <b>지금 전부 거짓</b>이다. 🔴 참인 사유가
     *           생기면 아래 분기가 그것을 집어낸다 — 그때 이 표를 고친다</td></tr>
     *   <tr><td>{@code LlmPermanentException} · {@code SandboxPermanentException}</td>
     *       <td>같은 요청을 다시 보내도 같다 — 절단 · 잘못된 요청 · 경로 위반</td></tr>
     * </table>
     *
     * <h2>🔴 일시 장애는 {@link RetryDecision.Defer} 다 — 멈추되 태우지 않는다 (#98)</h2>
     *
     * <p>{@code LlmTransientException} · {@code SandboxTransientException} · {@code WorkspaceException}
     * 은 <b>전송 계층 축이 이미 흡수한 뒤</b> 여기 온다({@code agent.llm.max-retries}). 그래서
     * 파이프라인 카운터로 또 세지 않는 것은 그대로다 — 다만 그것이 <b>종단</b>이어야 할 이유는
     * 없었다. 이미지가 없거나 데몬이 죽은 것은 후보의 코드와 무관하고 준비되면 같은 요청이 성공한다.
     * 초안은 이것까지 {@code Stop} 으로 보내 인프라 장애 한 번이 후보를 영구히 지웠다.
     *
     * <p>🔴 <b>이것도 화이트리스트다.</b> 「일시」임을 <b>타입이 스스로 말하는</b> 것만 미룬다
     * ({@code retryable()} · {@code WorkspaceException} javadoc). 모르는 예외는 여전히 {@code Stop} 이다 —
     * 「모르니까 미루자」로 두면 새 결함이 조용히 SELECTED 로 되돌아가 사람이 무한히 다시 누른다.
     *
     * <p>⚠️ 그래서 이 메서드는 <b>「무엇을 멈출까」를 열거하지 않는다.</b>
     * 기본이 {@code Stop} 이고, 재시도·미룸이 의미 있는 예외가 생기면 <b>그것만</b> 분기를 얻는다.
     */
    public static RetryDecision after(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("실패는 필수다");
        }
        // 🔴 일시 장애 화이트리스트 — 타입이 「다시 하면 될 수 있다」를 스스로 말하는 것만 (#98)
        if (isTransient(failure)) {
            return new RetryDecision.Defer(stageOf(failure),
                    "일시 장애로 미룬다 (" + failure.getClass().getSimpleName() + ") — 준비되면 다시 착수한다");
        }
        // 🔴 유일한 재시도 후보. 지금은 어떤 사유도 참이 아니라 이 분기가 타지 않는다 —
        //    그래도 둔다. 참인 사유가 생기는 날 여기가 그것을 집어내고,
        //    없으면 그날 조용히 Stop 으로 떨어진다
        if (failure instanceof DiffReviewRejectedException rejected
                && rejected.reason().retryable()) {
            return new RetryDecision.Retry(new CodingFeedback(
                    CodingFeedback.Kind.REVIEW,
                    java.util.List.of("직전 리뷰가 " + rejected.reason() + " 로 거부됐다")));
        }
        // ⚠ 예외 메시지를 싣지 않는다. 타입만으로 분류가 된다 —
        //   #18 이 「#9 가 진행 조회에 클래스 이름만 내보낸 것과 같은 판단」으로 둔 선이다
        return new RetryDecision.Stop(stageOf(failure),
                "재시도 불가 (" + failure.getClass().getSimpleName() + ")");
    }

    /**
     * 🔴 <b>어느 단계가 판정 불가였는지</b>까지 적는다 — 사람이 볼 유일한 신호다.
     *
     * <p>{@code UNDETERMINED} 는 「아무도 설명하지 않는 공백」이라 사람이 본다
     * ({@code glossary.md}). 「판정 불가」라고만 적으면 <b>어디를 볼지</b>가 없다.
     *
     * <p>⚠️ 단계 이름만 싣는다. {@code StageResult.summary} 는 스크럽됐어도 <b>빌드 출력</b>이라
     * {@code AgentRun.errorMessage} 와 로그로 내보내지 않는다 — #18 이 그은 선 그대로다.
     */
    private static String undeterminedReason(VerificationReport report) {
        String stages = report.outcomes().entrySet().stream()
                .filter(entry -> entry.getValue() == StageOutcome.UNDETERMINED)
                .map(entry -> entry.getKey().name())
                .reduce((a, b) -> a + "," + b)
                .orElse("?");
        return "검증 판정 불가 (" + stages + ") — 재시도해도 같다";
    }

    /**
     * 피드백 종류 → 실패를 기록할 단계.
     *
     * <p>🔴 <b>{@code default} 를 두지 않는다.</b> {@link CodingFeedback.Kind} 에 값이
     * 추가되면 컴파일이 깨진다 — 그것이 목적이다. {@code default} 를 두면 새 종류가
     * 조용히 {@code CODE} 로 분류되어 <b>「어디서 죽었나」가 틀린 채로 기록</b>된다.
     */
    private static AgentRun.Stage stageOf(CodingFeedback.Kind kind) {
        return switch (kind) {
            case COMPILE, TEST, DIFF -> AgentRun.Stage.VERIFY;
            case REVIEW -> AgentRun.Stage.REVIEW;
        };
    }

    /**
     * 예외 → 실패를 기록할 단계.
     *
     * <p>⚠️ 여기는 <b>열거일 수밖에 없다</b> — 예외 타입은 닫힌 집합이 아니라 컴파일러가
     * 빠짐을 잡아주지 못한다. 그래서 <b>모르는 것은 {@code CODE}</b> 로 간다:
     * 바퀴의 시작이고, 틀려도 「어느 단계인지 모른다」가 아니라 「가장 이른 단계」로 적힌다.
     *
     * <p>🔴 이 분류가 틀려도 <b>재시도 판정은 영향받지 않는다</b> — 그쪽은 위 화이트리스트가
     * 정하고 이것은 <b>기록의 라벨</b>일 뿐이다. 둘을 섞지 않는 것이 요점이다.
     */
    private static AgentRun.Stage stageOf(RuntimeException failure) {
        if (failure instanceof DiffReviewRejectedException) {
            return AgentRun.Stage.REVIEW;
        }
        if (failure instanceof VerificationSetupException || failure instanceof SandboxException) {
            return AgentRun.Stage.VERIFY;
        }
        if (failure instanceof LlmException llm && llm.callSite() == LlmCallSite.REVIEW) {
            return AgentRun.Stage.REVIEW;
        }
        return AgentRun.Stage.CODE;
    }

    /**
     * 「준비되면 같은 요청이 성공한다」를 <b>타입이 스스로 말하는가</b> — #98.
     *
     * <p>🔴 여기에 {@code RuntimeException} 일반을 넣지 않는다. 그것은 거부목록이다.
     * {@code WorkspaceException} 은 자기 javadoc 이 「우리가 작업을 수행하지 못한 경우 —
     * 네트워크·권한·경로. 재시도 루프의 「코드가 깨졌다」로 세지 않는다」고 못 박아 두었다.
     */
    public static boolean isTransient(RuntimeException failure) {
        if (failure instanceof SandboxException sandbox) {
            return sandbox.retryable();
        }
        if (failure instanceof LlmException llm) {
            return llm.retryable();
        }
        return failure instanceof WorkspaceException;
    }

    /**
     * 같은 실패가 반복되는가 — FR-5.
     *
     * <p>🕳 <b>발화하지 않을 수 있다.</b> 한계는 {@link FailureFingerprint} javadoc 맨 앞에 있다.
     *
     * @param previous 직전 바퀴의 지문. 첫 바퀴면 비어 있다
     * @return 반복이면 {@link RetryDecision.Stop}, 아니면 받은 결정 그대로
     */
    public static RetryDecision guardRepeat(RetryDecision decision,
            Optional<FailureFingerprint> previous) {
        if (!(decision instanceof RetryDecision.Retry retry)) {
            return decision;
        }
        FailureFingerprint current = FailureFingerprint.of(retry.feedback());
        if (previous.isPresent() && previous.get().equals(current)) {
            // 🔴 지문만 남긴다 — 원문은 들고 있지도 않다 (S-4)
            return new RetryDecision.Stop(stageOf(retry.feedback().kind()),
                    "같은 실패가 반복된다 (" + retry.feedback().kind() + " · " + current + ")");
        }
        return decision;
    }
}
