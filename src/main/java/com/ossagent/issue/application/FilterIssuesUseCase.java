package com.ossagent.issue.application;

import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.IssueFilter;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 수집된 이슈에 규칙 필터를 적용한다 — #9.
 *
 * <h2>이 단계가 존재하는 이유</h2>
 *
 * <p>걸러내지 않으면 <b>분석 비용이 그대로 늘어난다.</b> 규칙으로 떨어뜨릴 수 있는 것을
 * 모델에 보내는 것은 그냥 돈이다. 그래서 설계 기준이 「싸고 결정적」이고,
 * 이 UseCase 는 <b>대외 호출을 하나도 하지 않는다.</b>
 *
 * <h2>⚠ 이 PR 에 호출자가 없다</h2>
 *
 * <p>스캔이 끝난 뒤 자동으로 이어지지 않는다. 트리거(스케줄러·스캔 후속)는 #14 의 몫이고,
 * 여기까지는 「부르면 도는 것」이다. 「구현됐다」와 「동작한다」를 섞어 적지 않는다.
 */
@Service
public class FilterIssuesUseCase {

    private static final Logger log = LoggerFactory.getLogger(FilterIssuesUseCase.class);

    private final IssueFilterBatchWriter writer;
    private final IssueFilterProperties properties;
    private final IssueFilter filter;

    public FilterIssuesUseCase(IssueFilterBatchWriter writer, IssueFilterProperties properties) {
        this.writer = writer;
        this.properties = properties;
        this.filter = IssueFilter.of(properties.minBodyLength(), properties.maxCommentCount());
    }

    /**
     * 저장소 하나의 미판정 이슈를 판정한다.
     *
     * <p>🔴 <b>트랜잭션은 배치마다 짧게</b> 열린다. 저장소 하나에 이슈가 수천 건이고
     * {@code body} 는 TEXT 라, 전량을 한 트랜잭션에 올리면 메모리·커넥션 점유가 커진다.
     *
     * <p>⚠ 배치 상한에 걸리면 남은 것을 두고 끝낸다 — {@link FilterResult#hasMore()}.
     * 판정은 멱등하므로 다음 실행이 이어받는다.
     */
    public FilterResult filter(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }

        Map<FilterOutcome, Integer> total = new EnumMap<>(FilterOutcome.class);
        boolean hasMore = false;
        IssueFilterBatchWriter.Batch previous = null;

        for (int i = 0; i < properties.maxBatchesPerRun(); i++) {
            IssueFilterBatchWriter.Batch batch =
                    writer.judgeBatch(repositoryId, filter, properties.batchSize());

            // 🔴 앞 배치와 같은 구간이 다시 왔다 = 판정이 커밋되지 않았다.
            //    여기서 멈추지 않으면 상한까지 돌고 「전부 판정했다」를 반환한다 —
            //    DB 는 하나도 바뀌지 않았는데 로그도 반환값도 성공이다
            if (batch.repeats(previous)) {
                throw new IllegalStateException(
                        "판정이 진행되지 않았다 — 같은 배치가 반복된다. "
                                + "호출자가 트랜잭션을 감싸 flush 가 막히지 않았는지 본다 "
                                + "(repositoryId=" + repositoryId + ", firstId=" + batch.firstId() + ")");
            }
            previous = batch;

            batch.counts().forEach((outcome, count) -> total.merge(outcome, count, Integer::sum));

            if (batch.size() < properties.batchSize()) {
                // 배치를 못 채웠다 = 미판정 이슈를 다 읽었다.
                // ⚠ 이 사이에 스캔이 새 이슈를 넣으면 놓치지만, 다음 실행이 이어받는다
                hasMore = false;
                break;
            }
            hasMore = true;
        }

        FilterResult result = FilterResult.of(total, hasMore);
        // 이슈 본문·라벨은 남기지 않는다 — 판정 어휘와 수치만 (logging.md · S-4)
        log.info("이슈 필터 완료 repositoryId={} {}", repositoryId, result);
        warnIfNothingPasses(repositoryId, result);
        return result;
    }

    /**
     * 🔴 {@code PASSED} 가 한 건도 나오지 않았으면 경고한다.
     *
     * <p>「{@code PASSED} 는 도달 가능해야 한다」는 이 단계의 불변식인데, <b>설정만으로도
     * 깨진다</b> — {@code min-body-length} 를 크게 잡으면 전건이 보류가 된다. 그러면 하류
     * #11 은 보류를 통과로 취급할 수밖에 없고, 판정을 흐리지 않겠다는 설계가 무력해진다.
     *
     * <p>예외로 막지 않는 이유 — 「전건 보류」가 <b>정상인 경우</b>가 실제로 있다(대상
     * 저장소의 이슈가 전부 짧은 경우). 판정 자체를 실패시키면 되돌릴 수 없는 쪽으로 틀린다.
     * 신호만 남기고 사람이 본다.
     */
    private static void warnIfNothingPasses(Long repositoryId, FilterResult result) {
        if (result.judged() > 0 && result.countOf(FilterOutcome.PASSED) == 0) {
            log.warn("통과한 이슈가 한 건도 없다 repositoryId={} judged={} — "
                            + "issue.filter 임계가 너무 빡빡하지 않은지 본다",
                    repositoryId, result.judged());
        }
    }
}
