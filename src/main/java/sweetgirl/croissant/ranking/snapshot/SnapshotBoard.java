package sweetgirl.croissant.ranking.snapshot;

import java.util.List;
import java.util.function.Supplier;

/**
 * 스냅샷을 남길 판 하나. 줄은 {@link Supplier} 로 미뤄 둔다 — 판마다 짧은 트랜잭션 안에서 읽고 바로 쓰려고
 * (한 트랜잭션에 모든 판을 몰아 읽으면 구가 늘수록 락을 쥔 시간이 길어진다).
 *
 * @param board      WEEKLY | RECORDS | REGIONS | SHARE | BREAD
 * @param categoryId 빵집 판만, 사람 판은 0
 * @param region     '' = 전국, 예: '서울_동대문구'
 * @param rows       상위 N(등수 포함). 안 열린 판이면 빈 목록 — 그날 스냅샷을 지우기만 한다
 */
public record SnapshotBoard(String board, long categoryId, String region, Supplier<List<RankedSubject>> rows) {
}
