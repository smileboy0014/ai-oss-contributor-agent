package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import java.util.Locale;

/**
 * 계획이 지목한 파일 하나 — 이슈 #16 FR-1.
 *
 * <p>「무엇을 고칠 것인가」의 최소 단위다. 이 값이 {@code PlanValidator} 의 검사 대상이고,
 * #18 이 <b>이 목록 밖의 파일을 건드리면 중단</b>한다(이슈 #18 완료조건).
 *
 * @param path      저장소 기준 경로
 * @param change    무엇을 할 것인가
 * @param intent    왜·어떻게. <b>스크럽된 값</b>이다 — 모델 자유 텍스트다
 */
public record PlannedFile(
        String path,
        ChangeKind change,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String intent) {

    /** {@code intent} 의 길이 상한. 계획 하나에 파일이 여럿이라 각 항목이 짧아야 읽힌다 */
    private static final int MAX_INTENT_LENGTH = 1_000;

    /**
     * 파일에 가할 변경의 종류.
     *
     * <p>🔴 <b>{@code DELETE} 를 두지 않는다.</b> 기여 PR 에서 파일 삭제는 거의 없고,
     * 있다면 사람이 판단할 일이다. 어휘에 없으면 모델이 계획할 수 없다 —
     * 「표현 불가능하게 만든다」가 플래그보다 강한 방어다(S-2 의 draft 고정과 같은 수법).
     */
    public enum ChangeKind {
        /** 기존 파일을 고친다 */
        MODIFY,
        /** 새 파일을 만든다 */
        CREATE;

        /** 모르는 값은 예외다 — 조용히 {@link #MODIFY} 로 눕히지 않는다 */
        public static ChangeKind from(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new PlanRejectedException("변경 종류가 비어 있습니다");
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "MODIFY", "EDIT", "UPDATE" -> MODIFY;
                case "CREATE", "ADD", "NEW" -> CREATE;
                default -> throw new PlanRejectedException("모르는 변경 종류입니다: " + raw);
            };
        }

        public boolean isCreate() {
            return this == CREATE;
        }
    }

    public PlannedFile {
        if (path == null || path.isBlank()) {
            throw new PlanRejectedException("계획이 지목한 파일 경로가 비어 있습니다");
        }
        path = path.trim().replace('\\', '/');
        if (change == null) {
            throw new PlanRejectedException("변경 종류는 필수입니다 path=" + path);
        }
        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4. 모델이 프롬프트의 토큰을 되뱉을 수 있고,
        //    프롬프트에는 대상 저장소 파일 내용이 실려 나갔다
        intent = TokenRedactor.redact(intent == null ? "" : intent.trim());
        if (intent.length() > MAX_INTENT_LENGTH) {
            intent = intent.substring(0, MAX_INTENT_LENGTH);
        }
    }

    /**
     * 🔴 <b>{@code intent} 를 찍지 않는다.</b> 스크럽을 거쳤어도 모델 자유 텍스트이고,
     * 임의 텍스트를 로그 포맷에 넣는 것 자체가 인젝션 경로다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "PlannedFile[path=%s, change=%s, intentSize=%d]"
                .formatted(path, change, intent.length());
    }
}
