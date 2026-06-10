package sweetgirl.croissant.store.dto.projection;

import java.math.BigDecimal;

import sweetgirl.croissant.store.enums.SearchRegionType;

public interface StoreSearchRegionIndexProjection {

	Long getRegionId();

	String getRegionName();

	SearchRegionType getRegionType();

	BigDecimal getWeight();
}
