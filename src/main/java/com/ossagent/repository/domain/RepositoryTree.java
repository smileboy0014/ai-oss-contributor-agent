package com.ossagent.repository.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 대상 저장소의 파일 목록 한 벌 — 이슈 #15 선별의 입력.
 *
 * <p>호출 1회로 저장소 전체 경로를 받는다. 이것이 Code Search API 대신 트리를 택한 이유다
 * (PLAN-15 D-1) — Search 는 분당 30회라는 <b>별도 예산</b>을 쓰지만, 트리는 Core 예산에서
 * 저장소당 1회다.
 *
 * <h2>🔴 {@code truncated} 를 「없음」으로 읽지 않는다</h2>
 * GitHub 은 항목이 너무 많으면 트리를 잘라 주고 {@code truncated: true} 를 붙인다.
 * 그때 못 받은 경로는 <b>존재하지 않는 것이 아니라 못 본 것</b>이다.
 *
 * <p>다만 이 사실이 작업을 <b>실패시키지는 않는다.</b> 규약 판정(S-5)이 fail-closed 인 것과
 * 방향이 다르다 — 가르는 것은 보수성의 정도가 아니라 <b>실패의 방향이 되돌릴 수 있는가</b>다
 * ({@code external-deps.md}). 규약을 못 읽고 통과시키면 남의 저장소에 위반 PR 이 나가지만,
 * 관련 파일 하나를 못 보면 계획 품질이 떨어질 뿐이고 다시 돌리면 된다.
 * 그래서 <b>표시하고 계속한다.</b>
 *
 * @param sha       이 트리의 SHA. 어느 시점을 봤는지 추적하는 값이다
 * @param entries   항목 전체. 디렉터리·서브모듈이 섞여 있다
 * @param truncated GitHub 이 목록을 잘랐는가
 */
public record RepositoryTree(String sha, List<RepositoryTreeEntry> entries, boolean truncated) {

    public RepositoryTree {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 내용을 읽을 수 있는 항목만. 디렉터리·서브모듈은 빠진다 */
    public List<RepositoryTreeEntry> blobs() {
        return entries.stream().filter(RepositoryTreeEntry::isBlob).toList();
    }

    /**
     * 이 경로가 트리에 <b>파일로</b> 있는가.
     *
     * <p>의존성 확장(FR-7)이 쓴다 — import 에서 유추한 경로가 실재하는지 확인해야
     * 없는 파일에 호출을 낭비하지 않는다.
     */
    public boolean containsBlob(String path) {
        return path != null && blobs().stream().anyMatch(it -> it.path().equals(path));
    }

    /** 경로 집합 — 반복 조회가 잦은 호출자가 한 번 만들어 쓴다 */
    public Set<String> blobPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (RepositoryTreeEntry entry : blobs()) {
            paths.add(entry.path());
        }
        return paths;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 🔴 <b>경로를 나열하지 않는다.</b> 수천 줄이 되고, 내용이 아니어도 대량 payload 다. */
    @Override
    public String toString() {
        return "RepositoryTree[sha=%s, entries=%d, truncated=%s]"
                .formatted(sha, entries.size(), truncated);
    }
}
