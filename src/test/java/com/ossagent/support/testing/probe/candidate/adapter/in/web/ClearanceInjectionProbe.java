package com.ossagent.support.testing.probe.candidate.adapter.in.web;

import com.ossagent.repository.domain.PolicyClearance;

/**
 * 「외부가 통행증을 주입한다」의 미끼 — S-5.
 *
 * <p>패키지가 {@code …adapter.in.web} 라, {@code ApprovalGateArchitectureTest} 의
 * 「통행증은 {@code adapter/in} 경계를 넘지 않는다」 규칙이 <b>반드시 이것을 물어야 한다.</b>
 *
 * <h2>왜 미끼가 필요한가</h2>
 *
 * <p>그 규칙은 지금 <b>위반 0건</b>이다 — 운영 코드의 어떤 컨트롤러도 {@link PolicyClearance}
 * 를 모른다. 위반이 없으면 규칙이 <b>대상을 잘못 지목하고 있어도 초록</b>이고,
 * 그것이 지키는 것은 <b>S-5 게이트를 껍데기로 만드는 경로</b>다 — 통행증을 요청 바디로
 * 받는 순간 정책을 읽지 않은 외부가 구현 단계 통과권을 넘겨줄 수 있다.
 *
 * <p>⚠ 스프링 스테레오타입·매핑 애노테이션을 붙이지 않는다. test 클래스 디렉토리가
 * 런타임 클래스패스에 있어 컴포넌트 스캔에 실제로 잡힌다.
 * 🔴 <b>진짜 컨트롤러가 아니다.</b> 아무도 부르지 않는다.
 */
public final class ClearanceInjectionProbe {

    /** 이 저장소가 <b>절대 하지 않기로 한 것</b>을 그대로 적어 둔 것이다 — 규칙이 물어야 한다. */
    public Long 통행증을_바깥에서_받는다(PolicyClearance injected) {
        return injected.repositoryId();
    }
}
