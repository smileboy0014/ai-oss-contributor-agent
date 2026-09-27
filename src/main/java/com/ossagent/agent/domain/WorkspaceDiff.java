package com.ossagent.agent.domain;

import java.util.Set;

/**
 * 워크스페이스 변경분 — #18.
 *
 * <h2>🔴 경로 집합을 <b>따로</b> 들고 있는 이유</h2>
 *
 * <p>「계획에 없는 파일을 건드리면 중단」(이슈 완료 조건)을 판정하려면 <b>바뀐 경로의 집합</b>이
 * 필요하다. 통합 diff 텍스트에서 매번 파싱해 뽑으면 그 파싱이 판정의 정확도를 좌우하고,
 * 파싱이 틀리면 <b>조용히 덜 잡는다.</b>
 *
 * <p>만드는 쪽(JGit)이 이미 구조화된 목록을 갖고 있으므로 그대로 들고 나온다.
 *
 * <h2>⚠️ {@code unifiedDiff} 는 대상 저장소 텍스트다 — S-4</h2>
 *
 * <p>대상 저장소가 시크릿을 커밋해 뒀다면 <b>그것이 diff 에 그대로 실려 온다.</b>
 * 이 값을 로그에 찍거나 DB 에 넣는 쪽이 스크럽을 책임진다 —
 * {@code GeneratedChange} 의 생성 경로가 그 강제 지점이다.
 *
 * @param unifiedDiff 통합 diff 전문. 🔴 <b>스크럽 전 원문</b>이다
 * @param changedPaths 바뀐 경로 집합 — 저장소 루트 기준 상대경로
 */
public record WorkspaceDiff(String unifiedDiff, Set<String> changedPaths) {

    public WorkspaceDiff {
        if (unifiedDiff == null) {
            throw new IllegalArgumentException("diff 본문은 필수다 — 변경이 없으면 빈 문자열이다");
        }
        // 🔴 null 과 빈 집합을 가른다. null 은 「모른다」이고 빈 집합은 「바뀐 것이 없다」다.
        //    전자를 후자로 뭉개면 「계획 밖 파일이 없다」로 읽혀 게이트가 조용히 통과한다
        if (changedPaths == null) {
            throw new IllegalArgumentException("바뀐 경로 집합은 필수다 — 계획 범위를 판정할 수 없다");
        }
        changedPaths = Set.copyOf(changedPaths);
    }

    public boolean isEmpty() {
        return changedPaths.isEmpty();
    }

    /**
     * 🔴 계획 범위를 벗어난 경로 — 비어 있어야 한다.
     *
     * <p>⚠️ 이 판정은 <b>diff 기준</b>이다. LLM 출력 검증은 조기 차단이고,
     * <b>샌드박스에서 돈 포맷터가 건드린 것</b>은 여기서만 드러난다.
     */
    public Set<String> outsideOf(Set<String> plannedPaths) {
        if (plannedPaths == null) {
            throw new IllegalArgumentException("계획 경로 집합은 필수다");
        }
        return changedPaths.stream()
                .filter(path -> !plannedPaths.contains(path))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
