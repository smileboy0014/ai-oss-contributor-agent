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

    /** 경로 길이 상한. 이보다 긴 저장소 경로는 실재하지 않는다 */
    private static final int MAX_PATH_LENGTH = 400;

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
        // 🔴 path 도 모델 자유 텍스트다 — intent 만 스크럽하고 여기를 면제하면 비대칭이다.
        //    같은 응답에서 왔고, 같은 곳(로그 · 거부 사유 · 재생성 프롬프트)으로 나간다
        path = TokenRedactor.redact(path.trim().replace('\\', '/'));
        requireSafeShape(path);
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
     * 경로가 <b>우리가 다룰 수 있는 모양</b>인가.
     *
     * <h2>🔴 {@code CREATE} 경로는 실재 대조를 통과할 수 없다</h2>
     * {@code MODIFY} 는 「컨텍스트가 보여준 목록에 있는가」로 걸러지지만,
     * <b>{@code CREATE} 는 그 대조가 성립하지 않는다</b>(새 파일은 없는 것이 정상이다).
     * 그래서 사실상 <b>모델이 쓴 임의 문자열</b>이 그대로 흘러간다.
     *
     * <p>그 값을 받아 <b>실제로 파일을 만드는 것이 #18</b> 이다. {@code ../} 가 섞여 있으면
     * 샌드박스 워크스페이스 밖에 쓰게 된다. 여기서 모양을 막아 두면 그 경로 자체가 생기지 않는다.
     *
     * <p>⚠️ {@code repository} 의 {@code RepositoryPathPolicy} 와 같은 규칙이지만
     * <b>import 하지 않는다.</b> 남의 애그리거트에 정적 결합을 만드는 것보다
     * 짧은 규칙을 각자 갖는 편이 싸다 — 검사할 대상이 다르기도 하다(저쪽은 GitHub 이 준 경로,
     * 이쪽은 <b>모델이 지어낸</b> 경로다).
     *
     * <h2>🕳 한계 — 조용히 통과하는 것을 먼저 적는다</h2>
     * <ul>
     *   <li><b>인코딩·유니코드 변형</b>({@code %2e%2e} 류)은 세그먼트로 보이지 않아 통과한다.
     *       지금은 무해하다 — 이 값을 URL 로 쓰지 않고 아무도 디코드하지 않으므로
     *       {@code %2e%2e} 는 <b>그냥 그런 이름의 디렉터리</b>다. 그러나 중간에 디코드하는
     *       단계가 생기면 그 순간 뚫린다</li>
     *   <li>이 검사는 <b>문자열 모양</b>일 뿐이다. 🔴 <b>「워크스페이스 밖에 쓰지 않는다」의
     *       진짜 보증은 #18 이 해석된 실경로가 루트 하위임을 단언하는 것</b>이다 —
     *       {@code SandboxWorkspace} 가 {@code toRealPath()} 후에 단언하는 것과 같은 수법
     *       (#17 은 정규화 <b>전</b> 문자열을 검사하고 정규화 <b>후</b> 값을 써서 심볼릭 링크로
     *       한 번 뚫렸다). 여기는 <b>검사한 값이 곧 저장되는 값</b>이라 그 불일치는 없다</li>
     * </ul>
     */
    private static void requireSafeShape(String path) {
        if (path.length() > MAX_PATH_LENGTH) {
            throw new PlanRejectedException("계획이 지목한 경로가 너무 깁니다 length=" + path.length());
        }
        if (path.startsWith("/") || path.contains("://")) {
            throw new PlanRejectedException("계획이 지목한 경로가 저장소 상대 경로가 아닙니다");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                // 줄바꿈은 로그·프롬프트 양쪽에서 문제를 만든다
                throw new PlanRejectedException("계획이 지목한 경로에 제어문자가 있습니다");
            }
        }
        // 🔴 문자열 검사가 아니라 세그먼트 검사다 — "foo..bar" 를 무고하게 막지 않는다
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new PlanRejectedException("계획이 지목한 경로에 상위 참조·빈 구간이 있습니다");
            }
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
