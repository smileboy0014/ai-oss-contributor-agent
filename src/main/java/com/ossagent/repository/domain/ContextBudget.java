package com.ossagent.repository.domain;

/**
 * 컨텍스트 크기 상한과 소모 — 이슈 #15 FR-4.
 *
 * <p>PRD §12 의 「저장소 전체를 LLM 에 넘기지 않는다」를 <b>숫자로</b> 만든 것이다.
 * 상한이 없으면 파일 수십 개가 조용히 실려 나가고, 비용은 재시도 루프에서 곱해진다.
 *
 * <h2>🔴 절단을 조용히 하지 않는다</h2>
 * 예산이 떨어졌을 때 그냥 멈추면, 하류는 <b>「관련 파일이 이게 전부」</b>로 읽는다.
 * 그러면 모델이 전체를 봤다고 전제하고 계획을 세운다 — {@code agent.analysis.max-body-chars}
 * 가 「절단했다는 사실을 프롬프트에 함께 적는다」고 못 박은 것과 같은 이유다.
 * {@link #truncated()} 가 그 사실을 들고 나간다.
 *
 * <p>이 타입은 <b>불변</b>이다. 소모는 {@link #consume(int)} 가 새 값을 돌려준다 —
 * 가변 카운터를 두면 「예산을 이미 썼는가」가 호출 순서에 달리게 된다.
 */
public record ContextBudget(
        int maxFiles, int maxTotalChars, int maxFileChars,
        int usedFiles, int usedChars, boolean truncated) {

    public ContextBudget {
        if (maxFiles < 1 || maxTotalChars < 1 || maxFileChars < 1) {
            throw new IllegalArgumentException(
                    "컨텍스트 상한은 1 이상이어야 합니다: maxFiles=%d maxTotalChars=%d maxFileChars=%d"
                            .formatted(maxFiles, maxTotalChars, maxFileChars));
        }
    }

    /** 아무것도 쓰지 않은 예산 */
    public static ContextBudget of(int maxFiles, int maxTotalChars, int maxFileChars) {
        return new ContextBudget(maxFiles, maxTotalChars, maxFileChars, 0, 0, false);
    }

    /** 파일을 더 담을 여지가 있는가 — 파일 수·문자 수 둘 다 본다 */
    public boolean hasRoom() {
        return usedFiles < maxFiles && usedChars < maxTotalChars;
    }

    /**
     * 이 크기의 파일이 <b>통째로</b> 들어가는가.
     *
     * <p>🔴 잘라서 넣지 않는다. 잘린 소스는 모델을 헷갈리게 한다 — 닫히지 않은 블록을
     * 보고 문법 오류로 읽거나, 없는 메서드를 있다고 전제한다. 「절반이라도 낫다」가
     * 성립하지 않는 자료형이다.
     */
    public boolean fits(int chars) {
        return chars <= maxFileChars && usedChars + chars <= maxTotalChars && usedFiles < maxFiles;
    }

    /** 파일 하나를 담은 뒤의 예산 */
    public ContextBudget consume(int chars) {
        return new ContextBudget(maxFiles, maxTotalChars, maxFileChars,
                usedFiles + 1, usedChars + chars, truncated);
    }

    /** 담지 못한 것이 있었다고 표시한 예산 */
    public ContextBudget markTruncated() {
        return truncated ? this
                : new ContextBudget(maxFiles, maxTotalChars, maxFileChars,
                        usedFiles, usedChars, true);
    }
}
