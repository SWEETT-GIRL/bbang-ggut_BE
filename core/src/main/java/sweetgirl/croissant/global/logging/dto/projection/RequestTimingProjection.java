package sweetgirl.croissant.global.logging.dto.projection;

/** 요청 로그에서 응답시간 통계에 필요한 세 칸만 — 본문·UA 까지 실으면 하루치(최대 23만 행)가 무겁다. */
public interface RequestTimingProjection {

	String getEndpoint();

	Integer getResponseTimeMs();

	Integer getResponseStatus();
}
