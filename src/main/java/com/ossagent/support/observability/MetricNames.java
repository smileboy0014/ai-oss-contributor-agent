package com.ossagent.support.observability;

/**
 * 메트릭 이름과 태그 키 — <b>우리가 만드는 미터의 전체 목록</b>이다.
 *
 * <p>🔴 <b>가드 테스트가 이 목록을 기준으로 돈다.</b> 「등록된 모든 미터」를 훑으면
 * Boot 기본 미터({@code jvm.*}·{@code http.server.requests}·{@code hikaricp.*})까지
 * 걸려 <b>첫 실행에서 무너지거나, 예외 목록을 두느라 가드가 헐거워진다.</b>
 * 여기 없는 이름으로 미터를 만들면 가드가 보지 못하므로, <b>새 미터는 반드시 여기 등록</b>한다.
 *
 * <p>이름 규약 — Micrometer 관례대로 {@code .} 로 끊는다. Prometheus 로 내보내면
 * {@code _} 로 자동 변환된다.
 */
public final class MetricNames {

    private MetricNames() {
    }

    // ─────────────────────────── 미터 이름 ───────────────────────────

    /** LLM 호출 건수. 태그: {@code call.site} · {@code outcome} */
    public static final String LLM_CALLS = "ossagent.llm.calls";

    /**
     * LLM 토큰 누적. 태그: {@code call.site} · {@code direction}
     *
     * <p>🔴 <b>금액이 아니라 토큰이다.</b> 단가가 코드·설정 어디에도 없고 모델은
     * 환경변수로 바뀐다 — 박아 넣으면 <b>틀린 숫자를 자신 있게 보여준다.</b>
     * 환산은 후속 이슈다 (PLAN-25 A-1).
     */
    public static final String LLM_TOKENS = "ossagent.llm.tokens";

    /**
     * LLM 호출의 시도 번호 분포. 태그: {@code call.site}
     *
     * <p>⚠️ <b>{@code retry_count} 라는 이름을 쓰지 않는다.</b> 여기서 세는 것은
     * {@code AgentRunContext.attempt}(ANALYZE 는 항상 1)이고, PRD §26 의
     * {@code retry_count} 가 뜻하는 <b>파이프라인 사이클</b>과 같아지는 것은 #21 이후다.
     * 같은 이름을 먼저 쓰면 #21 이 의미를 바꿀 때 <b>메트릭이 조용히 다른 것을 세게 된다.</b>
     */
    public static final String LLM_CALL_ATTEMPT = "ossagent.llm.call.attempt";

    /** 파이프라인 단계 소요 시간·결과. 태그: {@code stage} · {@code outcome} */
    public static final String PIPELINE_STAGE = "ossagent.pipeline.stage";

    /**
     * 🔴 안전 게이트 판정. 태그: {@code clause} · {@code outcome} · {@code reason}
     *
     * <p><b>통과도 센다.</b> {@code logging.md} — 「안전 게이트 … 통과한 것도 남긴다.
     * 사고 후 「막았는가」를 증명할 수 있어야 한다」. 차단만 세면 분모가 없어
     * 「막힌 비율」을 계산할 수 없고, <b>「0건 차단」과 「계측 고장」이 구분되지 않는다.</b>
     */
    public static final String SAFETY_GATE = "ossagent.safety.gate";

    /** 이슈 분석의 <b>건당</b> 결과. 태그: {@code outcome} */
    public static final String ANALYSIS_OUTCOME = "ossagent.analysis.outcome";

    /** 후보 상태 분포 — 누적이 아니라 <b>현재 값</b>이다. 태그: {@code status} */
    public static final String CANDIDATE_COUNT = "ossagent.candidate.count";

    // ─────────────────────────── 태그 키 ───────────────────────────

    public static final String TAG_CALL_SITE = "call.site";
    public static final String TAG_DIRECTION = "direction";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_STAGE = "stage";
    public static final String TAG_CLAUSE = "clause";
    public static final String TAG_REASON = "reason";
    public static final String TAG_STATUS = "status";

    /**
     * 🔴 <b>태그 키로 쓰면 안 되는 것.</b> 가드 테스트가 이 목록을 쓴다.
     *
     * <p>시크릿은 아니지만 <b>카디널리티가 무한</b>이고, 식별자를 외부 모니터링
     * 시스템으로 계속 밀어낸다 — S-4 와 같은 방향이다.
     */
    public static final java.util.Set<String> FORBIDDEN_TAG_KEYS = java.util.Set.of(
            "candidateId", "candidate.id",
            "issueId", "issue.id",
            "repositoryId", "repository.id",
            "url", "uri", "title", "body", "message", "error");

    /** 우리가 만드는 미터의 이름 접두사 — 가드 테스트가 범위를 좁히는 데 쓴다. */
    public static final String PREFIX = "ossagent.";
}
