package com.ossagent.repository.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 이슈 #15 의 산출물 — <b>#16(구현 계획)이 LLM 에 넘길 입력</b>.
 *
 * <p>PRD §12 의 「저장소 전체를 LLM 에 넘기지 않는다」가 이 타입의 존재 이유다.
 * 수천 파일 중 <b>왜 이것들인지</b>를 함께 들고 다닌다.
 *
 * <h2>🔴 「비어 있음」이 오류가 아니다</h2>
 * 이슈가 클래스 이름도 경로도 스택트레이스도 적지 않았으면 {@link #files()} 가 빌 수 있다.
 * 그것은 실패가 아니라 <b>「좁힐 신호가 없었다」는 사실</b>이고, #16 이 그 사실을 보고
 * 판단해야 한다. 억지로 아무 파일이나 채우면 엉뚱한 계획이 선다.
 *
 * <h2>🔴 영속화하지 않는다</h2>
 * PLAN-15 D-2 — 이 값은 프로세스 안에서 #16 으로 전달되고 끝난다. 컬럼도 테이블도 없다.
 * 소비자가 생길 때 실제 필요를 보고 정한다.
 *
 * @param coordinates   어느 저장소인가
 * @param ref           어느 브랜치·커밋을 봤는가
 * @param treeSha       그때 트리의 SHA — 「무엇을 보고 골랐나」의 추적 값
 * @param files         고른 파일들. <b>점수 순</b>이다
 * @param budget        상한과 소모 (FR-4)
 * @param treeTruncated GitHub 이 트리를 잘라 줬는가 — 못 본 경로가 있다는 뜻이다
 * @param scannedPaths  점수를 매긴 후보 경로 수. 「모수」이고, 0 이면 선별이 아니라 수집이 실패한 것이다
 * @param excluded      배제 사유별 개수 (FR-5). 🔴 <b>경로 목록은 담지 않는다</b>
 */
public record RepositoryContext(
        RepositoryCoordinates coordinates,
        String ref,
        String treeSha,
        List<SelectedFile> files,
        ContextBudget budget,
        boolean treeTruncated,
        int scannedPaths,
        Map<ExcludedPathReason, Integer> excluded) {

    public RepositoryContext {
        if (coordinates == null) {
            throw new IllegalArgumentException("저장소 좌표는 필수입니다");
        }
        if (budget == null) {
            throw new IllegalArgumentException("컨텍스트 예산은 필수입니다 — 상한 없는 컨텍스트를 만들지 않는다 (FR-4)");
        }
        files = files == null ? List.of() : List.copyOf(files);
        excluded = excluded == null || excluded.isEmpty()
                ? Map.of()
                : Map.copyOf(new EnumMap<>(excluded));
        scannedPaths = Math.max(scannedPaths, 0);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public int totalChars() {
        return budget.usedChars();
    }

    /**
     * 담지 못한 것이 있었는가 — 예산 절단 <b>또는</b> 트리 절단.
     *
     * <p>둘을 합쳐 하나로 보여 주는 이유는 하류의 물음이 하나이기 때문이다 —
     * 「내가 본 것이 전부인가」. 원인 구분이 필요하면 {@link #budget()} 과
     * {@link #treeTruncated()} 를 따로 본다.
     */
    public boolean isPartial() {
        return budget.truncated() || treeTruncated;
    }

    public int excludedCount(ExcludedPathReason reason) {
        return excluded.getOrDefault(reason, 0);
    }

    /**
     * 🔴 <b>파일 내용도 경로 목록도 찍지 않는다.</b> 집계만 남긴다 — {@code logging.md} 의
     * 「대용량 payload 는 크기와 해시만」.
     */
    @Override
    public String toString() {
        return "RepositoryContext[repo=%s, ref=%s, files=%d, chars=%d, partial=%s, scanned=%d]"
                .formatted(coordinates.fullName(), ref, files.size(), totalChars(), isPartial(),
                        scannedPaths);
    }
}
