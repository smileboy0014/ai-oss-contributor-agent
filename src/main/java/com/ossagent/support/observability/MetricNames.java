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
     * <p>🔴 <b>금액이 아니라 토큰이다.</b> 금액은 {@link #LLM_COST} 가 따로 센다 —
     * 단가가 설정돼 있을 때만이다. <b>토큰은 언제나 세므로 단가를 나중에 알게 돼도
     * 소급해 곱할 수 있다.</b> 이 미터가 금액에 종속되지 않는 것이 그 이유다.
     */
    public static final String LLM_TOKENS = "ossagent.llm.tokens";

    /**
     * LLM 비용 누적({@link #CURRENCY}). 태그: {@code call.site} — 이슈 #71.
     *
     * <p>🔴 <b>단가가 없으면 이 미터가 아예 없다.</b> 0 으로 두면 「공짜」로 읽히고,
     * 그것은 「모른다」와 전혀 다른 말이다. 단가는 설정
     * ({@code agent.llm.pricing.<model>.input}/{@code .output})에서만 온다 —
     * 코드에 박으면 <b>틀린 숫자를 자신 있게 보여준다.</b>
     *
     * <p>🔴 <b>모델을 태그로 달지 않는다.</b> 모델 ID 는 설정 문자열이라 우리가 통제하는
     * 어휘가 아니고, {@code PipelineMetrics} 가 「시그니처가 enum 만 받는다」로 지키는
     * 성질이 그 태그 하나로 무너진다. 어느 모델의 값인지는 <b>기동 로그</b>가 답한다.
     *
     * <p>⚠️ <b>메트릭이 사라져도 비용은 다시 셀 수 있다.</b> {@code agent_run} 에
     * 호출마다 입·출력 토큰이 남으므로, 단가를 곱하면 누적 비용이 나온다
     * ({@code SUM(input_tokens)} · {@code SUM(output_tokens)}).
     * ⚠️ 다만 그 행에는 <b>모델이 남지 않는다</b> — 중간에 모델을 바꿨다면 그 집계는
     * 구간을 갈라 계산해야 한다.
     */
    public static final String LLM_COST = "ossagent.llm.cost";

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

    /**
     * 규약 문서 재확인 결과 — 이슈 #68. 태그: {@code outcome}
     *
     * <p>🔴 {@code CHANGED_UNVERIFIABLE} 이 0 이 아니면 <b>S-5 위반이 진행 중일 수 있다.</b>
     * 대상 저장소가 규약을 바꿨는데 우리가 그 문서를 읽지 못한 상태다.
     */
    public static final String POLICY_DOCUMENTS = "ossagent.policy.documents";

    /**
     * 새 후보 알림 건수 — 이슈 #26 완료조건 2. 태그 없음.
     *
     * <p>🔴 <b>이 값이 0 에 붙어 있으면 「알림 경로가 이름만 있다」는 뜻이다.</b>
     * 완료조건이 「알림 경로」인데 로그만 두면 그것이 실제로 발화하는지 아무도 모른다 —
     * 대시보드가 먼저 말해 줘야 하는 종류의 고장이다.
     *
     * <p>⚠️ {@link #ANALYSIS_OUTCOME}({@code outcome=ANALYZED})과 값이 <b>같아야 한다.</b>
     * 갈리면 알림이 빠진 경로가 있다는 뜻이고, 그 차이가 유일한 증거다.
     */
    public static final String CANDIDATE_NOTIFIED = "ossagent.candidate.notified";

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

    /**
     * 금액 미터의 단위 — Micrometer {@code baseUnit} 으로 싣는다.
     *
     * <p>🔴 <b>태그가 아니다.</b> 통화를 태그로 두면 값이 하나뿐인 태그가 모든 시계열에
     * 붙고, 「통화를 바꿀 수 있다」는 인상까지 준다. 단가는 USD 로만 적는다.
     */
    public static final String CURRENCY = "USD";

    /** 우리가 만드는 미터의 이름 접두사 — 가드 테스트가 범위를 좁히는 데 쓴다. */
    public static final String PREFIX = "ossagent.";
}
