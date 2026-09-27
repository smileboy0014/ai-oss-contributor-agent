package com.ossagent.candidate.domain;

import com.ossagent.repository.domain.ContributionConstraints;
import java.util.Set;

/**
 * 검증 단계에 넘기는 <b>값</b> — #18 이 만들고 #19 가 소비한다.
 *
 * <h2>🔴 {@code GeneratedChange} 를 넘기지 않는다</h2>
 *
 * <p>{@code GeneratedChange} 는 <b>독립 애그리거트</b>다({@code architecture.md} 의 애그리거트 표).
 * 능력 시그니처에 넣으면 구현이 남의 애그리거트 엔티티를 import 하게 되고, 규율 ④ 가
 * 막는 결합이 <b>능력 인터페이스를 통해</b> 되살아난다.
 *
 * <h2>왜 {@code workspacePath} 가 {@link String} 인가</h2>
 *
 * <p>{@code SandboxWorkspace}({@code agent/domain})는 <b>생성 시점에 루트 하위임을 단언</b>한다.
 * 문자열로 넘기면 구현이 그것을 다시 만들면서 <b>검증이 한 번 더 돈다</b> — S-3 방어가
 * 이음매에서 공짜로 한 겹 더 선다. 검증된 타입을 그대로 넘기면 그 재검증이 사라진다.
 *
 * <p>🔴 <b>이 값을 로그·{@code AgentRun.errorMessage}·PR 본문에 싣지 않는다</b> (S-4).
 * <b>호스트 절대경로</b>라, 대상 저장소나 모델에게 우리 디렉토리 구조를 알려 줄 이유가 없고
 * 사고 후 로그가 그대로 공유되는 경로다. 구현(#19)이 빌드 출력과 함께 흘려보내기 가장 쉬운
 * 자리이므로 여기에 적어 둔다 — 컨테이너 안 경로({@code /workspace})는 고정이라
 * 로그에 필요하면 그쪽을 쓴다.
 *
 * @param candidateId  후보 식별자 — 로그·{@code AgentRun} 상관관계용
 * @param attempt      🔴 <b>호출자가 넘긴다.</b> {@code AgentRun} 불변식이
 *                     「같은 사이클의 행이 같은 {@code attempt} 값을 갖는다」(Q-6)이므로,
 *                     검증기가 스스로 세면 {@code CODE} 행과 {@code VERIFY} 행이 갈린다.
 *                     세는 주체는 <b>사이클을 아는 쪽</b>이고 그것은 호출자다
 * @param workspacePath 대상 저장소가 체크아웃된 호스트 경로 (위 참조)
 * @param constraints  대상 저장소 규약의 <b>값</b> — 규율 ④ 예외. 엔티티가 아니다
 * @param plannedPaths 🔴 계획이 허용한 경로 집합. <b>계획 범위 밖 변경</b> 판정에 쓴다 —
 *                     없으면 그 검사를 할 수 없다
 */
public record VerificationRequest(
        Long candidateId,
        int attempt,
        String workspacePath,
        ContributionConstraints constraints,
        Set<String> plannedPaths) {

    public VerificationRequest {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수다");
        }
        if (attempt < 1) {
            // 🔴 0 을 허용하면 AgentRun 의 사이클 번호가 1부터라는 전제가 깨진다.
            //    그러면 CODE 행과 VERIFY 행을 이어 붙일 수 없다 — 증상은 「조회가 비는 것」이라
            //    예외보다 발견이 늦다
            throw new IllegalArgumentException("사이클 번호는 1 이상이다: " + attempt);
        }
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalArgumentException("워크스페이스 경로는 필수다 — 검증할 대상이 없다");
        }
        if (constraints == null) {
            throw new IllegalArgumentException("대상 저장소 규약은 필수다 — 빌드·테스트 명령이 거기 있다");
        }
        // 🔴 빈 집합과 null 을 가른다. null 은 「모른다」이고 빈 집합은 「아무 파일도 허용 안 됨」이다.
        //    전자를 후자로 뭉개면 계획 범위 검사가 조용히 「전부 위반」이 된다
        if (plannedPaths == null) {
            throw new IllegalArgumentException("계획 경로 집합은 필수다 — 범위 밖 변경을 판정할 수 없다");
        }
        plannedPaths = Set.copyOf(plannedPaths);
    }
}
