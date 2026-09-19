package sweetgirl.croissant.personalization.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TasteTagTest {

	@Test
	@DisplayName("사전은 dataops #11 v5 의 8키다 — dataops 가 store_profile.tags 에 넣는 키와 글자까지 같아야 한다")
	void keysFollowDataopsV5() {
		assertThat(Arrays.stream(TasteTag.values()).map(TasteTag::getKey))
			.containsExactly("웨이팅", "머물기", "공간사진", "가성비", "건강한맛", "특별한메뉴", "대형", "디저트");
	}

	@Test
	@DisplayName("키는 데이터 이름이라 공백 · 문장부호가 없다. 앱에 보이는 글자는 표시 문구가 따로 맡는다")
	void keysHaveNoPunctuation() {
		assertThat(TasteTag.values()).allSatisfy(tag -> {
			assertThat(tag.getKey()).matches("[가-힣]+");
			assertThat(tag.getLabel()).isNotBlank();
		});
		assertThat(TasteTag.STAY.getLabel()).isEqualTo("머물기 좋은");
		assertThat(TasteTag.SPACE_PHOTO.getLabel()).isEqualTo("사진");
	}

	@Test
	@DisplayName("키로 찾는다 — 없어진 옛 키(카페형 · 노포 …)나 모르는 키는 비어 있다")
	void fromKey() {
		assertThat(TasteTag.fromKey("공간사진")).contains(TasteTag.SPACE_PHOTO);
		assertThat(TasteTag.fromKey("카페형")).isEmpty();
		assertThat(TasteTag.fromKey("공간·사진")).isEmpty();
		assertThat(TasteTag.fromKey(null)).isEmpty();
	}
}
