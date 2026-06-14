package sweetgirl.croissant.store.dto.projection;

public interface HotStoreRankingTrendProjection {

	Long getFamousMenuByRegionId();

	int getRank();

	Long getStoreId();

	String getStoreName();

	String getStoreAddress();
}
