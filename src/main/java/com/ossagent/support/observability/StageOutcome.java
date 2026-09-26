package com.ossagent.support.observability;

/** 파이프라인 단계의 끝. */
public enum StageOutcome {
    SUCCEEDED,
    /** 🔴 <b>실패가 아니다</b> — 규약이 막았거나 읽지 못했거나 레이트리밋이다 */
    SKIPPED,
    FAILED
}
