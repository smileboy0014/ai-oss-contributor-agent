package com.ossagent.candidate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 코딩 단계 설정 — {@code agent.coding.*} (#18).
 *
 * <h2>🔴 위치가 {@code application} 인 이유 — 가드가 알려 줬다</h2>
 *
 * <p>처음에 {@code adapter/out/llm} 에 두고 「값만 들고 있어 판정에 걸리지 않는다」고
 * 적었다. <b>틀렸다.</b> {@code ExternalAdapterIsolationTest} 는 <b>패키지</b>도 신호로 쓰므로
 * 그 자리에 있는 것만으로 잡힌다 — 빌드가 바로 빨개졌다.
 *
 * <p>⚠️ 그 가드를 고치지 않고 <b>이쪽을 옮겼다.</b> 가드가 「기술 이름 패키지에 있는 빈은
 * 테스트 컨텍스트에서 빠져야 한다」를 보는 것이고, 설정값이 거기 있을 이유가 없다 —
 * {@code ImplementationPlanProperties} 가 같은 이유로 이미 {@code application} 에 있다.
 *
 * <h2>🔴 출력 예산이 계획 단계보다 커야 한다</h2>
 *
 * <p>계획은 「파일 목록 + 의도」라 짧지만 코딩은 <b>파일 전체 내용</b>을 돌려받는다.
 * 예산이 모자라면 응답이 <b>잘리고</b>, 잘린 JSON 은 파싱 실패로 나타난다 —
 * {@code external-deps.md} 가 「상한 절단·거부는 성공이 아니라 예외다」로 못 박은 자리다.
 *
 * <p>⚠️ <b>절단은 전송 재시도 대상이 아니다.</b> 같은 상한으로 재전송하면 같은 지점에서
 * 잘려 입력 토큰만 배로 태운다. 고칠 주체는 <b>계획의 범위</b>이지 재시도가 아니다.
 *
 * @param maxOutputTokens 코딩 응답의 출력 예산
 */
@ConfigurationProperties("agent.coding")
public record CodingProperties(int maxOutputTokens) {

    public CodingProperties {
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException(
                    "agent.coding.max-output-tokens 는 1 이상이어야 한다: " + maxOutputTokens);
        }
    }
}
