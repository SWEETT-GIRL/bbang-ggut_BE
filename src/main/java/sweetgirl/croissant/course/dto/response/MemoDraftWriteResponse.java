package sweetgirl.croissant.course.dto.response;

/** PUT memo-draft 응답. 옛 seq 를 보냈으면 Redis 에 지금 있는 더 큰 seq 가 돌아온다 — 앱은 자기 시계를 그 위로 올린다. */
public record MemoDraftWriteResponse(long acceptedSeq) {
}
