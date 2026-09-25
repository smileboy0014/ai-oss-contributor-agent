package com.ossagent.agent.adapter.out.llm;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 어댑터 매핑 층의 대역 — <b>소켓 없이</b> 요청 조립·응답 파싱·오류 번역만 본다.
 *
 * <p>Q-9 이 지정한 중간 층 도구 {@code MockRestServiceServer} 는 Spring {@code RestClient}
 * 전용이라 SDK 어댑터에는 바인딩 대상이 없다. 대신 SDK 가 공개하는 이음매
 * ({@code ClientOptions.Builder.httpClient})에 이것을 꽂는다 — <b>층은 유지하고 도구만 바꾼다.</b>
 *
 * <p>소켓이 필요한 것(읽기 타임아웃·연결 실패)은 전송 계약 층에서 WireMock 이 본다.
 *
 * <p>보낸 요청 본문을 보관하므로 <b>스크럽이 실제로 적용됐는지</b>를 송신 본문에서 확인할 수 있다 —
 * S-4 검증의 핵심이다. 로그나 예외가 아니라 <b>나가는 바이트</b>를 봐야 의미가 있다.
 */
final class StubHttpClient implements HttpClient {

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<String> sentBodies = new ArrayList<>();

    /** 이 JSON 본문을 200 으로 돌려준다. */
    StubHttpClient respondJson(String json) {
        responses.add(new Canned(200, json));
        return this;
    }

    StubHttpClient respondStatus(int status) {
        responses.add(new Canned(status, "{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"x\"}}"));
        return this;
    }

    /** 전송 자체가 터지는 경우 — 연결 실패·읽기 타임아웃이 이렇게 온다. */
    StubHttpClient failWith(RuntimeException error) {
        responses.add(error);
        return this;
    }

    /** 실제로 나간 요청 본문들. 스크럽 검증에 쓴다. */
    List<String> sentBodies() {
        return List.copyOf(sentBodies);
    }

    int sentCount() {
        return sentBodies.size();
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        sentBodies.add(readBody(request));
        if (responses.isEmpty()) {
            throw new IllegalStateException("준비된 응답이 없다 — 예상보다 많이 호출됐다");
        }
        Object next = responses.poll();
        if (next instanceof RuntimeException error) {
            throw error;
        }
        Canned canned = (Canned) next;
        return new CannedResponse(canned.status(), canned.json());
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request,
            RequestOptions requestOptions) {
        return CompletableFuture.completedFuture(execute(request, requestOptions));
    }

    @Override
    public void close() {
        // 닫을 자원이 없다
    }

    private static String readBody(HttpRequest request) {
        var out = new ByteArrayOutputStream();
        request.body().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private record Canned(int status, String json) {
    }

    private record CannedResponse(int status, String json) implements HttpResponse {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Headers headers() {
            return Headers.builder().put("content-type", "application/json").build();
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            // 버퍼라 닫을 것이 없다
        }
    }
}
