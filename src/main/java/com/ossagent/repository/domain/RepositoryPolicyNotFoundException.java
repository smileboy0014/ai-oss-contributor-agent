package com.ossagent.repository.domain;

/**
 * 저장소에 기여 규약 행이 <b>아직 없다</b> — S-5 · #24.
 *
 * <p>🔴 <b>「보류」와 다르다.</b> 보류는 「읽으려 했으나 못 읽었다」가 <b>기록된</b> 상태이고,
 * 이것은 <b>기록조차 없는</b> 상태다 — 분석이 5xx·레이트리밋·타임아웃으로 중단되면
 * 행이 아예 만들어지지 않는다({@code AnalyzeRepositoryPolicyUseCase} 의 중단 경로).
 *
 * <p>여기서 해소를 허용하면 <b>「읽지 않고 허용」</b> 이 된다. S-5 가 막으려는 것이
 * 정확히 그것이므로, 해소가 아니라 <b>분석을 먼저</b> 돌려야 한다.
 */
public class RepositoryPolicyNotFoundException extends RuntimeException {

    public RepositoryPolicyNotFoundException(Long repositoryId) {
        super("저장소에 기여 규약이 아직 없습니다 — 분석을 먼저 돌려야 합니다 repositoryId=" + repositoryId);
    }
}
