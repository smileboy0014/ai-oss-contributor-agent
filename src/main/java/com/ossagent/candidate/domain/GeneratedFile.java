package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.SecretFilePolicy;
import com.ossagent.support.secret.TokenRedactor;

/**
 * 코딩 에이전트가 만든 <b>파일 하나의 최종 내용</b> — #18.
 *
 * <h2>🔴 스크럽을 compact 생성자가 강제한다 — S-4</h2>
 *
 * <p>{@code content} 는 <b>모델이 만든 자유 텍스트</b>다. 모델은 대상 저장소의 코드 조각을
 * 컨텍스트로 받았고, <b>그 저장소가 시크릿을 커밋해 뒀다면 모델이 그것을 그대로
 * 되뱉을 수 있다.</b> 이 값은 diff 로, 다시 {@code GeneratedChange} 로 흘러간다.
 *
 * <p>{@code SelectedFile}(#15)이 같은 수법으로 유입 방향을 막았다면 이것은 <b>산출 방향</b>이다.
 * 생성 경로가 하나뿐이므로 <b>스크럽을 건너뛸 수 없다.</b>
 *
 * <p>⚠️ <b>{@code with…} 류나 {@code String} 을 받는 팩토리를 만들지 않는다.</b> 하나만 생겨도
 * 이 보증이 우회되고, <b>기존 테스트는 그대로 초록</b>이다 — 그 테스트는 우회 경로를 모른다
 * (#73 의 「가드가 있다 ≠ 이 입력에 닿는다」).
 *
 * @param path    저장소 루트 기준 상대경로. 🔴 <b>계획에 있는 경로여야 한다</b> — 검증은 호출자
 * @param content 파일 전체 내용. <b>스크럽된 값</b>이다
 */
public record GeneratedFile(
        String path,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String content) {

    public GeneratedFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("생성 파일 경로는 필수다");
        }
        // 🔴 경로 배제 — 내용 스크럽이 이것을 대신하지 않는다 (glossary 「혼동 주의」).
        //
        //   유입 방향(#15 의 RepositoryContext)에는 SecretFilePolicy 가 걸려 있지만
        //   **산출 방향에는 없었다.** 모델이 .env·id_rsa·secrets/x.pem 을 돌려주면
        //   이 타입이 그대로 받고, 그 내용이 워크스페이스에 쓰여 diff 로, 다시
        //   GeneratedChange 로 흘러간다.
        //
        //   ⚠ CodingOutOfPlanException 이 대신하지 못한다 — 그것은 **계획에 없을 때만**
        //   막는다. 계획(#16)은 대상 저장소 트리에서 뽑히므로 .env 가 계획에 들어갈
        //   가능성이 0 이라고 말할 근거가 없다.
        //
        //   🔴 과차단 쪽으로 닫는다. 키 파일을 고치는 기여는 사람이 판단할 일이고,
        //   막혀서 잃는 것은 되돌릴 수 있다.
        if (SecretFilePolicy.isSecretPath(path)) {
            throw new IllegalArgumentException(
                    "시크릿 경로에는 쓰지 않는다 — 기여 대상이 아니다 (S-4): " + path);
        }
        if (content == null) {
            // 🔴 빈 문자열과 null 을 가른다. 빈 파일은 유효한 산출이고 null 은 「모델이 안 줬다」다.
            //    뭉개면 빈 파일을 써 놓고 「만들었다」로 보고하게 된다
            throw new IllegalArgumentException("생성 파일 내용은 필수다 — 빈 파일이면 빈 문자열이다");
        }
        content = TokenRedactor.redact(content);
    }
}
