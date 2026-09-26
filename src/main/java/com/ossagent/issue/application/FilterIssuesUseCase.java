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

        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Map<FilterOutcome, Integer> counts =
                    writer.judgeBatch(repositoryId, filter, properties.batchSize());
            counts.forEach((outcome, count) -> total.merge(outcome, count, Integer::sum));

            int judged = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (judged < properties.batchSize()) {
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
        return result;
    }
}
