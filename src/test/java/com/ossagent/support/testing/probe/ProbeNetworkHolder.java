package com.ossagent.support.testing.probe;

import org.springframework.web.client.RestClient;

/**
 * 판정기 <b>신호 2(네트워크 클라이언트 보유)</b>의 미끼다.
 *
 * <p>패키지에 {@code github}·{@code llm}·{@code sandbox} 가 <b>하나도 없다.</b>
 * 그런데도 {@code RestClient} 를 들고 있으므로 대외 어댑터로 판정되어야 한다.
 *
 * <p>이 미끼가 지키는 것은 <b>「패키지 이름을 바꿔 가드를 통과시키는」 회피</b>다.
 * 신호 1 만 있으면 {@code support.github} → {@code support.client} 로 옮기는 것만으로
 * 가드가 <b>코드 변경 없이 영구 무력화</b>된다. 신호 2 는 이름이 아니라 능력을 본다.
 *
 * <p>⚠ 스프링 스테레오타입을 붙이지 않는다 — 붙이면 컴포넌트 스캔에 잡혀 가드가
 * 스스로를 잡는다.
 */
public final class ProbeNetworkHolder {

    /** 실제로 쓰지 않는다. 판정기가 <b>필드 타입</b>을 보는지 확인하기 위한 것이다. */
    private final RestClient restClient = null;

    private ProbeNetworkHolder() {
    }

    RestClient unused() {
        return restClient;
    }
}
