package com.ossagent.support.testing;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * 대외 의존 페이크의 <b>조립 지점</b>이다 — Q-9 확정(2026-09-25).
 *
 * <p>Q-9 는 대역을 <b>층으로</b> 갈랐다. 층마다 보는 것이 달라 하나로 통일할 수 없다.
 * <b>이 클래스가 담당하는 것은 맨 위 「능력 소비자」 층뿐</b>이다 — 나머지 층은 스프링
 * 컨텍스트를 쓰지 않으므로 여기에 등록되지 않는다.
 *
 * <table border="1">
 *   <caption>층별 대역</caption>
 *   <tr><th>층</th><th>대역</th><th>어디서</th></tr>
 *   <tr><td><b>능력 소비자</b> (UseCase)</td><td>자체 페이크</td><td><b>여기</b></td></tr>
 *   <tr><td>어댑터 매핑</td><td>{@code MockRestServiceServer}</td><td>어댑터 테스트 (컨텍스트 밖)</td></tr>
 *   <tr><td>전송 계약</td><td>WireMock</td><td>어댑터 테스트 — 읽기 타임아웃·리다이렉트 거부</td></tr>
 *   <tr><td>PostgreSQL</td><td>Testcontainers (실제)</td><td>Q-9 범위 밖 · Q-2b 확정</td></tr>
 * </table>
 *
 * <p>HTTP 가 아닌 두 경로는 층이 다르게 선다 — 능력 페이크만으로는 안전 경계를 증명하지 못한다.
 * <ul>
 *   <li><b>git 전송</b>(clone · branch · push) — 🔴 S-1. 대역은 실제 원격이 아니라
 *       <b>push 시도를 기록</b>하면 된다. 「upstream 좌표면 중단하고 Fork 좌표면 위임한다」를
 *       실제 원격 없이 검증한다. 실제 git 원격을 타는 자동 테스트를 만들지 않는다</li>
 *   <li><b>컨테이너 제어</b>(docker 호출) — 🔴 S-3. 「타임아웃 시 컨테이너가 <b>정리된다</b>」는
 *       능력 페이크로 증명되지 않는다. 제어 호출을 기록하는 대역이
 *       「timeout 후 remove 가 불렸는가」를 본다. 실제 컨테이너는 띄우지 않는다</li>
 * </ul>
 *
 * <p><b>지금 비어 있는 것이 정상이다.</b> {@code main} 에 대외 능력 인터페이스가 아직 없다
 * (#6 이 열려 있고 미머지). 능력이 생기면 그 페이크를 여기에 {@code @Bean} 으로 등록한다.
 *
 * <p>페이크를 여기 등록할 때 지킬 것 — 상세는 {@code testing-philosophy.md} 「픽스처 규약」.
 * <ul>
 *   <li>이름은 {@code Fake{능력이름}}. {@code Mock}·{@code Stub} 을 쓰지 않는다 —
 *       Mockito 의 mock 과 섞인다</li>
 *   <li>페이크는 자기가 구현하는 <b>능력 인터페이스와 같은 패키지</b>에 둔다
 *       (예: {@code com.ossagent.issue.domain.FakeIssueSource})</li>
 *   <li><b>값 픽스처</b>는 자기가 만드는 타입과 같은 패키지에 {@code {타입\}Fixtures} 로 둔다.
 *       static factory 만, 상태 없음.
 *       <br>🔴 <b>애그리거트를 넘어 공유하지 않는다.</b> {@code candidate} 테스트가
 *       {@code IssueFixtures} 를 쓰면 규율 ④ 가 막는 의존이 <b>테스트를 통해 되살아난다</b>.
 *       남의 애그리거트 값이 필요하면 자기 테스트 패키지에서 자기가 만든다 —
 *       중복이 결합보다 싸다</li>
 *   <li><b>리소스 픽스처</b>는 용도별로 가른다. 첫 픽스처가 생길 때 디렉토리를 만든다
 *       (빈 디렉토리는 git 이 추적하지 않는다).
 *       <pre>
 *   src/test/resources/github/{api}-{case}.json        HTTP 응답 본문
 *   src/test/resources/policy/{case}/CONTRIBUTING.md   기여 규약 원문 — S-5 판정 입력
 *   src/test/resources/llm/{prompt}-{case}.txt         LLM 응답 원문 (깨진 출력 포함)
 *       </pre></li>
 *   <li>🔴 <b>실패 모드를 재현할 수 있어야 한다</b> — 예외 · 빈 결과 · 깨진 LLM 출력.
 *       「항상 성공만 반환하는 페이크」는 게이트를 검증하지 못한다. 이 제품의 품질 축은
 *       「좋은 코드를 쓰는가」가 아니라 <b>「나쁜 결과를 걸러내는가」</b>다</li>
 *   <li>🔴 <b>픽스처에 실제 토큰을 넣지 않는다 (S-4).</b> 토큰 <i>형태</i>가 필요하면
 *       <b>패턴에 매칭되지 않는, 가짜임이 눈에 보이는 고정 상수</b>를 쓴다 —
 *       {@code "ghp_NOT_A_REAL_TOKEN_FOR_TESTS_ONLY"} (밑줄이 있어
 *       {@code secret-scan.sh} 의 {@code ghp_[A-Za-z0-9]{36\}} 에 걸리지 않는다).
 *       <br>⚠ {@code "ghp_" + "x".repeat(36)} 같은 <b>런타임 조립을 기본 관용구로 삼지 않는다.</b>
 *       그것은 「토큰을 안 쓴다」가 아니라 <b>「검사를 피한다」</b>이고, 스캐너 회피 관용구를
 *       저장소 전체에 퍼뜨린다. 토큰의 길이·문자셋이 <i>실제로 유의미한</i> 테스트
 *       (마스킹 경계 검증 등)에서만 조립하고, <b>왜 조립했는지를 그 줄에 주석으로 남긴다</b></li>
 * </ul>
 *
 * <p>🔴 페이크는 {@code src/test} 에만 존재한다. {@code src/main} 에 페이크·스텁·인메모리
 * 구현을 두지 않는다 — 운영 조립에서 실수로 선택될 수 있는 경로를 만들지 않기 위해서다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeExternalDependencies {
}
