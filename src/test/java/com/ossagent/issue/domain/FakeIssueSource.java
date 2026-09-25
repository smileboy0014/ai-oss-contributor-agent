package com.ossagent.issue.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link IssueSource} 의 테스트 대역 — Q-9 확정에 따른 자체 페이크.
 *
 * <p>페이지를 큐로 넣어 두면 호출 순서대로 돌려준다. 증분 수집(#8)의 커서 진전과
 * 멱등성(스캔 재실행이 중복 후보를 만들지 않는다)을 검증할 때 이 순서가 필요하다.
 *
 * <h2>실패도 순서대로 재현한다 (#8)</h2>
 *
 * <p>{@link #failWith} 하나로는 <b>모든 호출이 똑같이 실패</b>해서
 * 「3페이지까지 읽고 그다음에 리밋」 같은 시나리오를 만들 수 없다. 그래서 응답 큐에
 * <b>성공과 실패를 섞어</b> 넣을 수 있게 한다 — 부분 수집 보존(#8 §3.3)이
 * 그 시나리오 없이는 검증되지 않는다.
 *
 * <p>⚠ <b>이 페이크로 검증할 수 없는 것이 하나 있다.</b> {@link IssueSource} 가 계약한
 * {@code updated_at} <b>오름차순</b>은 여기서 재현되지 않는다 — 페이크는 넣어 준 순서를
 * 돌려줄 뿐이다. 그 보장은 <b>어댑터 테스트</b>가 실제 쿼리 파라미터를 고정해 지킨다.
 */
@FakeAdapter
public class FakeIssueSource implements IssueSource {

    /** 성공 페이지 또는 던질 예외. 한 호출에 하나씩 소비된다. */
    private record Response(IssuePage page, RuntimeException failure) {

        static Response ok(IssuePage page) {
            return new Response(page, null);
        }

        static Response error(RuntimeException failure) {
            return new Response(null, failure);
        }
    }

    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<IssueQuery> queries = new ArrayList<>();
    private RuntimeException alwaysFail;

    public FakeIssueSource given(IssuePage page) {
        responses.addLast(Response.ok(page));
        return this;
    }

    public FakeIssueSource givenIssues(IssueSnapshot... issues) {
        return given(new IssuePage(List.of(issues), null, false, false));
    }

    /** 다음 페이지가 더 있는 응답 — 페이지네이션 검증용. */
    public FakeIssueSource givenPageWithNext(String etag, IssueSnapshot... issues) {
        return given(new IssuePage(List.of(issues), etag, false, true));
    }

    /** 304 — 마지막 수집 이후 바뀐 것이 없다. */
    public FakeIssueSource givenNotModified(String etag) {
        return given(IssuePage.unchanged(etag));
    }

    /**
     * <b>이 순서의 호출에서만</b> 던진다. 앞서 넣어 둔 성공 응답들은 그대로 소비된다.
     *
     * <p>「N 페이지까지 읽고 그다음에 리밋」을 만드는 수단이다.
     */
    public FakeIssueSource thenFailWith(RuntimeException exception) {
        responses.addLast(Response.error(exception));
        return this;
    }

    /** 모든 호출을 실패시킨다 — 권한 오류처럼 회복 불가능한 경로용. */
    public FakeIssueSource failWith(RuntimeException exception) {
        this.alwaysFail = exception;
        return this;
    }

    /**
     * 🔴 <b>테스트마다 {@code @BeforeEach} 에서 부른다.</b>
     *
     * <p>페이크는 싱글턴이고 스프링 컨텍스트는 테스트 클래스 사이에 캐시된다.
     * 초기화하지 않으면 앞 테스트가 넣어 둔 {@code failWith} 나 남은 응답이
     * <b>다음 테스트로 샌다</b> — 실제로 겪은 실패다.
     */
    public FakeIssueSource reset() {
        responses.clear();
        queries.clear();
        alwaysFail = null;
        return this;
    }

    /** 실제로 들어온 조회 조건. 커서·ETag·페이지가 올바로 전달되는지 확인한다. */
    public List<IssueQuery> queries() {
        return List.copyOf(queries);
    }

    public int callCount() {
        return queries.size();
    }

    @Override
    public IssuePage fetchOpenIssues(IssueQuery query) {
        queries.add(query);
        if (alwaysFail != null) {
            throw alwaysFail;
        }
        if (responses.isEmpty()) {
            // 더 넣어 둔 응답이 없으면 「빈 마지막 페이지」다 — 무한 루프를 만들지 않는다
            return new IssuePage(List.of(), null, false, false);
        }
        Response next = responses.removeFirst();
        if (next.failure() != null) {
            throw next.failure();
        }
        return next.page();
    }
}
