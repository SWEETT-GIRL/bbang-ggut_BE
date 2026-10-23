package sweetgirl.croissant.breadlog.repository;

/** 칭호 집계 한 줄 — 동네(region_key)와 그 동네 공개 빵로그 수. */
public interface BreadRecordTownProjection {

	String getRegionKey();

	long getRecordCount();
}
