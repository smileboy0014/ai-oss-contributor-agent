package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;

/**
 * 계획 단계(#16)로 넘어가는 파일 한 개 — 내용과 <b>고른 이유</b>를 함께 든다.
 *
 * <h2>🔴 이 타입이 스크럽 강제 지점이다 (S-4)</h2>
 * {@link #content} 는 <b>{@link TokenRedactor} 를 거쳐야만 만들어진다.</b>
 * {@code String} 을 그대로 받는 생성 경로가 없다 — {@code ScrubbedRules}(#7) ·
 * {@code IssueAnalysis}(#11) 와 같은 수법이다.
 *
 * <p>⚠️ <b>{@code SecretFilePolicy} 가 이것을 대신하지 않는다.</b> 그쪽은 경로를 보고
 * <b>애초에 열지 않는</b> 방어이고, 여기는 <b>연 파일의 내용</b>을 가리는 방어다.
 * 둘은 겹치는 것이 아니라 <b>순서가 다르다</b>({@code SecretFilePolicy} javadoc).
 * {@code src/main/java/SomeConfig.java} 에 토큰이 하드코딩돼 있으면 경로 정책을
 * <b>정상 통과</b>하고, 그것을 막는 것이 여기다.
 *
 * <p>⚠️ 그렇다고 「내용이 깨끗하다」는 뜻은 아니다. {@link TokenRedactor} 는
 * <b>알려진 패턴만</b> 가린다. 이 타입이 보장하는 것은 <b>「스크럽을 거치지 않은 값이
 * 들어갈 수 없다」</b>이지 「시크릿이 없다」가 아니다.
 *
 * @param path    저장소 기준 경로
 * @param reason  고른 이유 (FR-5)
 * @param score   점수. 같은 컨텍스트 안에서만 비교 의미가 있다
 * @param content <b>스크럽된</b> 파일 내용
 */
public record SelectedFile(
        String path,
        SelectionReason reason,
        int score,
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String content) {

    public SelectedFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("선별된 파일의 경로가 비어 있습니다");
        }
        if (reason == null) {
            throw new IllegalArgumentException("선별 사유는 필수입니다 — 왜 골랐는지 모르면 고칠 수 없다 (FR-5)");
        }
        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4.
        //    대상 저장소가 시크릿을 커밋해 뒀을 수 있고, 이 값은 #16 에서 LLM 프롬프트로 간다
        content = TokenRedactor.redact(content == null ? "" : content);
    }

    public int size() {
        return content.length();
    }

    /**
     * 🔴 <b>내용을 찍지 않는다.</b> 스크럽을 거쳤어도 대상 저장소에서 온 임의 텍스트이고,
     * {@code logging.md} 는 대용량 payload 에 대해 「크기와 해시만」을 요구한다.
     *
     * <p>이 override 가 없으면 record 기본 {@code toString} 이 파일 본문을 통째로 내보낸다 —
     * 로그 한 줄이나 예외 메시지 하나로 충분하다.
     */
    @Override
    public String toString() {
        return "SelectedFile[path=%s, reason=%s, score=%d, size=%d]"
                .formatted(path, reason, score, size());
    }
}
