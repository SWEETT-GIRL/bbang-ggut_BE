package sweetgirl.croissant.store.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.store.enums.AxisSource;

class StoreProfileTest {

	private static final LocalDateTime COMPUTED_AT = LocalDateTime.of(2026, 9, 6, 11, 40);

	@Test
	@DisplayName("네 축이 다 있으면 축 근거가 있는 가게다")
	void hasAxis_whenComplete() {
		TasteAxis saltBreadShop = TasteAxis.of(
			new BigDecimal("0.810"), new BigDecimal("0.900"), new BigDecimal("0.900"), new BigDecimal("0.810"));
		StoreProfile profile = StoreProfile.builder()
			.storeId(1042L)
			.axis(saltBreadShop)
			.axisSource(AxisSource.BOTH)
			.breadType(saltBreadShop.typeCode())
			.tags(Map.of("웨이팅", 0.8, "특별한메뉴", 0.6))
			.attrs(Map.of("parking", false))
			.kinds(new String[] {"소금빵", "깜빠뉴"})
			.signatureMenus(new String[] {"소금빵"})
			.bakeryScore(new BigDecimal("0.920"))
			.reviewCount(143)
			.ratingCount(16)
			.reviewScore(new BigDecimal("4.87"))
			.storeStatus("ACTIVE")
			.batchVersion("v1")
			.computedAt(COMPUTED_AT)
			.build();

		assertThat(profile.hasAxis()).isTrue();
		assertThat(profile.getTags()).containsEntry("웨이팅", 0.8);
		assertThat(profile.getKinds()).containsExactly("소금빵", "깜빠뉴");
		assertThat(profile.getBatchVersion()).isEqualTo("v1");
		assertThat(profile.getReviewScore()).isEqualByComparingTo("4.87");
		assertThat(profile.getRatingCount()).isEqualTo(16);
	}

	@Test
	@DisplayName("축이 없는 가게는 근거 부족이고, 태그·속성은 비어 있어도 null 이 아니다")
	void hasAxis_falseWithoutAxis_andEmptyJsonDefaults() {
		StoreProfile profile = StoreProfile.builder()
			.storeId(7L).bakeryScore(BigDecimal.ZERO).storeStatus("ACTIVE").batchVersion("v1")
			.computedAt(COMPUTED_AT).build();

		assertThat(profile.hasAxis()).isFalse();
		assertThat(profile.getTags()).isEmpty();
		assertThat(profile.getAttrs()).isEmpty();
	}

	@Test
	@DisplayName("축이 하나라도 비면 벡터로 쓰지 않는다")
	void hasAxis_falseWhenPartial() {
		TasteAxis partial = TasteAxis.of(new BigDecimal("0.500"), null, new BigDecimal("0.500"), null);
		StoreProfile profile = StoreProfile.builder()
			.storeId(7L).axis(partial).axisSource(AxisSource.MENU).bakeryScore(new BigDecimal("0.500"))
			.storeStatus("ACTIVE").batchVersion("v1").computedAt(COMPUTED_AT).build();

		assertThat(profile.hasAxis()).isFalse();
	}
}
