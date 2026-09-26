package com.ossagent.agent.domain;

import java.util.List;

/**
 * 샌드박스에서 한 번 돌릴 것 — #17.
 *
 * <h2>🔴 sealed 인 이유 — 네트워크를 「고를 수 있게」 두면 증명할 것이 없다</h2>
 *
 * <p>처음 설계는 {@code SandboxNetwork} 를 <b>필드</b>로 받았다. 그러면
 * <b>「네트워크 개방 + 대상 저장소 명령」이 타입상 합법</b>이고, 그것은 신뢰할 수 없는
 * 코드를 네트워크가 열린 채로 돌리는 바로 그 상태다. 테스트 「워밍에서만 열린다」가
 * 단언할 대상이 없었다 — 기껏해야 「기본값이 닫힘」이다.
 *
 * <p>생성 경로를 갈라 그 조합을 <b>표현 불가능</b>하게 만든다. S-2 의 「draft 플래그를
 * 두면 언젠가 켜진다」와 같은 논리다.
 *
 * <table border="1">
 *   <caption>세 가지뿐이다</caption>
 *   <tr><th></th><th>네트워크</th><th>명령</th><th>워크스페이스</th><th>캐시 볼륨</th></tr>
 *   <tr><td>{@link WarmCommand}</td><td>열림</td><td><b>우리 것</b></td><td>RW</td>
 *       <td>🔴 <b>마운트하지 않는다</b></td></tr>
 *   <tr><td>{@link SeedCacheCommand}</td><td>없음</td><td><b>우리 것</b>({@code cp})</td>
 *       <td>RO</td><td>RW</td></tr>
 *   <tr><td>{@link ExecuteCommand}</td><td>없음</td><td>대상 저장소 것</td><td>RW</td>
 *       <td>RO</td></tr>
 * </table>
 *
 * <p>🔴 <b>워밍이 캐시 볼륨을 마운트하지 않는 것이 핵심이다.</b> 워밍은 신뢰할 수 없는
 * 코드를 네트워크가 열린 채로 돌린다. 그 단계가 볼륨에 쓸 수 있으면
 * {@code init.d/*.gradle} 같은 것을 심어 <b>다음 워밍에서 자동 실행</b>시킬 수 있고,
 * 볼륨은 후보 수명을 넘겨 지속되므로 「워밍 명령은 우리가 정한다」가 우회된다.
 * 볼륨에 쓰는 것은 {@link SeedCacheCommand} 뿐이고, 그것은 <b>우리 {@code cp} 를
 * 네트워크 없이</b> 돌린다.
 *
 * <h2>환경변수를 받는 자리가 없다 — S-4</h2>
 *
 * <p>「시크릿을 넘기지 마라」를 문서로 두면 언젠가 {@code Map<String,String> env} 가 생긴다.
 * 세 구현 어디에도 그 필드가 없고, 컨테이너에 들어가는 변수는 어댑터가 단계별로 계산한다.
 *
 * <p>⚠ 이 사실을 리플렉션 테스트로 확인하지 않는다 — 이름 기반이라 우회가 쉽고,
 * 검사 대상이 「자리의 부재」라 record 컴포넌트가 곧 계약이다. 테스트는 대신
 * <b>실제로 만들어진 컨테이너 설정에 화이트리스트 밖 변수가 없는가</b>를 본다.
 */
public sealed interface SandboxCommand
        permits WarmCommand, SeedCacheCommand, ExecuteCommand {

    /** 바인드할 워크스페이스. 세 단계가 <b>같은 것</b>을 써야 한다 — wrapper 배포본이 여기 있다. */
    SandboxWorkspace workspace();

    /** 컨테이너에서 실행할 argv. 🔴 <b>쉘을 경유하지 않는다</b> — S-4. */
    List<String> argv();

    SandboxLimits limits();

    /** 컨테이너 이미지. 대상 저장소 문자열이 그대로 들어오지 않는다 — {@link SandboxImages}. */
    String image();
}
