package sweetgirl.croissant.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.entity.TasteLabelVector;

@DataJpaTest
@Import({StoreLabelStatsRepository.class, JpaConfig.class})
class StoreLabelStatsRepositoryTest {

	@Autowired
	private StoreLabelStatsRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("표가 비어 있으면 null — 0 으로 중심화하면 달콤한 가게만 몰아준다")
	void emptyTableGivesNull() {
		assertThat(repository.findLabelMeans()).isNull();
	}

	@Test
	@DisplayName("아홉 평균을 TasteLabelVector.values() 와 같은 순서로 준다")
	void meansKeepVectorOrder() {
		// 값을 전부 다르게 넣어 순서가 뒤바뀌면 반드시 드러나게 한다
		insert(1, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);
		insert(2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 0.1);

		double[] means = repository.findLabelMeans();

		assertThat(means).hasSize(TasteLabelVector.DIMENSION);
		assertThat(means[0]).isEqualTo(0.2); // taste_sweet
		assertThat(means[1]).isEqualTo(0.3); // taste_plain
		assertThat(means[2]).isEqualTo(0.4); // taste_salty
		assertThat(means[3]).isEqualTo(0.5); // taste_aroma
		assertThat(means[4]).isEqualTo(0.6); // tex_soft
		assertThat(means[5]).isEqualTo(0.7); // tex_crisp
		assertThat(means[6]).isEqualTo(0.8); // tex_chewy
		assertThat(means[7]).isEqualTo(0.9); // filling_rich
		assertThat(means[8]).isEqualTo(0.5); // vibe_trendy
	}

	private void insert(long storeId, double... values) {
		jdbcTemplate.update("""
				INSERT INTO store_recommendation_feature (store_id, taste_sweet, taste_plain, taste_salty,
					taste_aroma, tex_soft, tex_crisp, tex_chewy, filling_rich, vibe_trendy,
					label_source, menu_label_count, review_label_count, source_fingerprint, dictionary_version,
					computed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOTH', 1, 1, '00000000000000000000000000000000',
					'v3-test', CURRENT_TIMESTAMP)
				""", storeId, values[0], values[1], values[2], values[3], values[4], values[5], values[6],
			values[7], values[8]);
	}
}
