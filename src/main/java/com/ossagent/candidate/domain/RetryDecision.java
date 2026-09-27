package com.ossagent.candidate.domain;

/**
 * 한 바퀴가 끝난 뒤 <b>무엇을 할 것인가</b> — #21.
 *
 * <p>{@code sealed} 인 이유는 {@link RetryPolicy} 의 판정이 <b>세 갈래뿐</b>이라는 것을
 * 타입으로 말하기 위해서다. 호출자가 {@code switch} 로 받으면 {@code default} 없이
 * 컴파일되고, 갈래가 늘면 <b>호출부가 깨진다</b> — 그것이 목적이다.
 *
 * <p>⚠️ <b>여기에 「왜」를 담지만 「무엇을」은 담지 않는다.</b> 전이를 부르는 것은
 * {@code application} 이다 — 도메인 값이 {@code candidate.retryImplementation(...)} 을
 * 부르면 판정과 부작용이 한 덩어리가 되어 순수 함수로 테스트할 수 없게 된다.
 */
public sealed interface RetryDecision {

    /**
     * 통과했다 — {@code REVIEWING → READY_FOR_PR}.
     *
     * <p>🔴 <b>여기가 자동화의 끝이 아니다.</b> 그 다음은 <b>세 번째 승인 게이트</b>(#23)이고,
     * 이 값은 PR 을 만들라는 뜻이 아니다 (S-2).
     */
    record Proceed() implements RetryDecision {}

    /**
     * 다시 돈다 — {@code TESTING·REVIEWING → IMPLEMENTING}.
     *
     * <p>⚠️ <b>상한을 여기서 보지 않는다.</b> 「더 돌 수 있는가」는
     * {@code ContributionCandidate.retryImplementation} 이 {@code attempt} 로 판정한다 —
     * 카운터의 주인이 후보 루트이기 때문이다(불변식 ⑧). 이 값은 <b>「재시도가 의미
     * 있는가」</b>만 말한다. 둘을 한곳에서 보면 도메인 상수가 두 군데가 된다.
     *
     * @param feedback 다음 바퀴 프롬프트에 되먹일 것. 🔴 <b>{@code null} 이 아니다</b> —
     *                 고칠 대상 없는 재시도는 예산만 태운다
     */
    record Retry(CodingFeedback feedback) implements RetryDecision {
        public Retry {
            if (feedback == null) {
                throw new IllegalArgumentException(
                        "되먹일 피드백 없이 재시도하지 않는다 — 같은 입력에 같은 결과다");
            }
        }
    }

    /**
     * 끝낸다 — {@code → FAILED} (종단).
     *
     * <p>🔴 <b>상한 소진이 아니어도 여기로 온다.</b> 「고칠 수 없는 실패」가 대부분이고,
     * 그 자체가 <b>사람에게 넘기는 신호</b>다 (S-6).
     *
     * @param stage  🔴 <b>어느 단계에서 멈췄나.</b> 값이 직접 들고 있어야 한다 —
     *               사유 문자열에서 되짚으면 <b>문구를 바꾸는 순간 조용히 어긋난다</b>
     *               ({@code testing-philosophy.md} 「거부목록으로 방어하지 않는다」).
     *               {@code AgentRun.Stage} 는 같은 도메인 패키지라 규율 ④ 에 걸리지 않는다
     * @param reason 🔴 <b>우리 어휘로만.</b> 빌드 출력·모델 응답·예외 메시지를 그대로 넣지
     *               않는다 — {@code AgentRun.errorMessage} 와 로그로 나가는 값이다 (S-4).
     *               그래도 {@code AgentRun.fail} 이 스크럽하는 것은 <b>마지막 그물</b>이지
     *               이 규약의 대체가 아니다
     */
    record Stop(AgentRun.Stage stage, String reason) implements RetryDecision {
        public Stop {
            if (stage == null) {
                throw new IllegalArgumentException("어느 단계에서 멈췄는지는 필수다");
            }
            if (reason == null || reason.isBlank()) {
                // 사유 없는 종단은 사람이 볼 것이 없다 — FAILED 가 「신호」인 이유가 사라진다
                throw new IllegalArgumentException("종단 사유는 필수다 — FAILED 는 되돌릴 수 없다");
            }
        }
    }
}
