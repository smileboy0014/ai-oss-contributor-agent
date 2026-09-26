package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import java.util.List;

/**
 * 「무엇을 어떻게 고칠 것인가」 — PRD §13 의 산출물이자 #18 의 입력.
 *
 * <h2>🔴 이 타입은 두 가지를 강제한다 — {@code IssueAnalysis}(#11) 와 같은 수법</h2>
 *
 * <ol>
 *   <li><b>스키마</b> — 생성자를 통과했다는 것이 곧 「모양은 쓸 수 있다」는 뜻이다.
 *       파일 0건, 중복 경로는 여기서 걸린다</li>
 *   <li><b>스크럽</b> — 자유 텍스트는 <b>스크럽을 거쳐야만</b> 만들어진다 (S-4).
 *       프롬프트에 대상 저장소 파일이 실려 나갔고, 모델이 그것을 되뱉을 수 있다</li>
 * </ol>
 *
 * <h2>「쓸 수 있는가」는 이 타입이 판정하지 않는다</h2>
 * 지목한 파일이 <b>실재하는지</b>, 규약을 <b>지키는지</b>, 범위가 <b>이슈를 넘는지</b>는
 * 여기 없다. 그것은 {@link PlanValidator} 가 <b>컨텍스트·규약과 대조</b>해야 알 수 있고,
 * 값 타입이 바깥 사실을 알 수는 없다.
 *
 * <p>⚠️ 그래서 <b>「생성자를 통과했다 = 실행해도 된다」가 아니다.</b> 이 구분이 무너지면
 * 검증 단계가 우회되고, 이 이슈의 존재 이유가 사라진다.
 *
 * @param files         고칠 파일들. <b>최소 1건</b>이다 — 아무것도 안 고치는 계획은 계획이 아니다
 * @param summary       계획 요약. <b>스크럽된 값</b>
 * @param testStrategy  테스트 전략. <b>스크럽된 값</b>. 비어 있을 수 있고,
 *                      그것이 규약 위반인지는 {@link PlanValidator} 가 판정한다
 * @param estimatedLoc  변경 예상 라인 수
 */
public record ImplementationPlan(
        List<PlannedFile> files,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String summary,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String testStrategy,
        int estimatedLoc) {

    private static final int MAX_SUMMARY_LENGTH = 2_000;
    private static final int MAX_TEST_STRATEGY_LENGTH = 2_000;

    public ImplementationPlan {
        if (files == null || files.isEmpty()) {
            throw new PlanRejectedException("계획에 고칠 파일이 없습니다 — 그것은 계획이 아닙니다");
        }
        files = List.copyOf(files);
        // 같은 파일을 두 번 지목하면 #18 이 무엇을 기준으로 할지 알 수 없다.
        // 모델이 「A 를 고치고 A 에 테스트를 더한다」로 쪼개는 일이 실제로 흔하다
        long distinct = files.stream().map(PlannedFile::path).distinct().count();
        if (distinct != files.size()) {
            throw new PlanRejectedException("계획이 같은 파일을 여러 번 지목했습니다 files=" + files.size());
        }
        if (estimatedLoc < 0) {
            throw new PlanRejectedException("변경 예상 라인 수가 음수입니다: " + estimatedLoc);
        }
        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4
        summary = truncate(TokenRedactor.redact(requireText(summary, "summary")),
                MAX_SUMMARY_LENGTH);
        testStrategy = truncate(TokenRedactor.redact(testStrategy == null ? "" : testStrategy.trim()),
                MAX_TEST_STRATEGY_LENGTH);
    }

    /** 테스트 전략이 실질적으로 적혀 있는가 — 규약 검사(FR-3)가 이것을 본다 */
    public boolean hasTestStrategy() {
        return !testStrategy.isBlank();
    }

    /** 계획이 새로 만들려는 파일들 — 「실재하는가」 검사에서 제외해야 한다 */
    public List<PlannedFile> createdFiles() {
        return files.stream().filter(it -> it.change().isCreate()).toList();
    }

    /** 계획이 고치려는 <b>기존</b> 파일들 — 이것들만 실재 검사 대상이다 */
    public List<PlannedFile> modifiedFiles() {
        return files.stream().filter(it -> !it.change().isCreate()).toList();
    }

    public List<String> paths() {
        return files.stream().map(PlannedFile::path).toList();
    }

    public int fileCount() {
        return files.size();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PlanRejectedException(field + " 가 비어 있습니다");
        }
        return value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 🔴 <b>요약·전략 본문을 찍지 않는다.</b> 경로는 남긴다 — 「무엇을 고치려 했나」가
     * 관측의 핵심이고(D-2 로 영속화하지 않으므로 로그가 유일한 기록이다), 경로는
     * 본문이 아니다.
     */
    @Override
    public String toString() {
        return "ImplementationPlan[files=%d, loc=%d, hasTestStrategy=%s, paths=%s]"
                .formatted(files.size(), estimatedLoc, hasTestStrategy(), paths());
    }
}
