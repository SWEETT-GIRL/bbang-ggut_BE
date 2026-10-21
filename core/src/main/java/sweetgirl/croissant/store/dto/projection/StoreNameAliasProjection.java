package sweetgirl.croissant.store.dto.projection;

/** 가게명 별칭(구명) 색인용 조회 — storeId 별 alias 문자열. */
public interface StoreNameAliasProjection {

	Long getStoreId();

	String getAlias();
}
