package com.ossagent.support.observability;

/**
 * 이슈 분석 1건의 귀결 — {@code AnalysisResult} 의 네 값과 1:1.
 *
 * <p>⚠️ {@code REJECTED} 는 <b>실패가 아니다.</b> 임계에 걸러진 것은 정상 동작이고,
 * 성공률을 어떻게 정의할지는 대시보드가 정한다 — 우리는 네 값을 그대로 준다.
 */
public enum AnalysisOutcome {
    ANALYZED,
    REJECTED,
    FAILED,
    /** 이미 후보가 있어 건너뛰었다 — 멱등이 작동한 것이다 */
    SKIPPED
}
