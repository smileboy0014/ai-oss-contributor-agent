package com.ossagent.candidate.domain;

import java.util.Set;
import java.util.TreeSet;

/**
 * 🔴 모델이 <b>계획 밖 경로</b>를 돌려줬다 — #18.
 *
 * <p>이슈 완료 조건 「계획에 없는 파일을 건드리면 중단」의 <b>조기 차단</b>이다.
 * 여기서 멈추면 워크스페이스에 아무것도 쓰이지 않는다.
 *
 * <h2>⚠️ 이것이 게이트의 전부가 아니다</h2>
 *
 * <p>샌드박스에서 도는 <b>포맷터</b>가 워크스페이스를 RW 로 잡고 임의 파일을 고친다.
 * 그것은 모델 출력에 나타나지 않으므로 이 예외로 잡히지 않는다 —
 * <b>최종 게이트는 diff 의 경로 집합</b>이다.
 *
 * <h2>⚠️ 재시도 대상이 아니다</h2>
 *
 * <p>같은 계획과 같은 프롬프트로 다시 부르면 같은 결과가 나올 가능성이 높다.
 * 재시도 예산(Q-6 의 3바퀴)은 <b>「코드가 깨졌다」</b>에 쓴다 — 여기에 태우면
 * 고칠 수 없는 것에 3바퀴를 쓰고 후보가 코드 문제 없이 {@code FAILED} 로 떨어진다.
 */
public class CodingOutOfPlanException extends RuntimeException {

    private final Set<String> outsidePaths;

    public CodingOutOfPlanException(Long candidateId, Set<String> outsidePaths) {
        // ⚠ 경로는 대상 저장소 텍스트다. 길이가 예측 불가이므로 정렬해 앞부분만 남긴다 —
        //   메시지 전체가 로그 한 줄을 삼키면 사람이 그 줄을 흘려본다
        super("모델이 계획 밖 경로를 돌려줬다 candidateId=%s outside=%s"
                .formatted(candidateId, summarize(outsidePaths)));
        this.outsidePaths = Set.copyOf(outsidePaths);
    }

    public Set<String> outsidePaths() {
        return outsidePaths;
    }

    private static String summarize(Set<String> paths) {
        TreeSet<String> sorted = new TreeSet<>(paths);
        if (sorted.size() <= 5) {
            return sorted.toString();
        }
        return sorted.stream().limit(5).toList() + " 외 " + (sorted.size() - 5) + "건";
    }
}
