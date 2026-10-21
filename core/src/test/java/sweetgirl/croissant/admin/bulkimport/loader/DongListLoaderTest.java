package sweetgirl.croissant.admin.bulkimport.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DongListLoaderTest {

	@Test
	@DisplayName("classpath의 dong_list.csv를 파싱해 DongRow 리스트로 반환한다")
	void load_parsesCsvFromClasspath() {
		DongListLoader loader = new DongListLoader();

		List<DongRow> rows = loader.load();

		assertThat(rows).isNotEmpty();
		assertThat(rows.get(0).region1st()).isNotBlank();
		assertThat(rows.get(0).region2nd()).isNotBlank();
		assertThat(rows.get(0).region3rd()).isNotBlank();
	}

	@Test
	@DisplayName("헤더 행은 결과에 포함되지 않는다")
	void load_skipsHeader() {
		DongListLoader loader = new DongListLoader();

		List<DongRow> rows = loader.load();

		assertThat(rows).noneMatch(r -> "region_1st".equals(r.region1st()));
	}
}
