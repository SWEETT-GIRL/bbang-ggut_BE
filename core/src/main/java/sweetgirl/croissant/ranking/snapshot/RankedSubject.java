package sweetgirl.croissant.ranking.snapshot;

/** 스냅샷 한 줄 — 사람 판은 member_id, 빵집 판은 store_id. */
public record RankedSubject(long subjectId, int rank) {
}
