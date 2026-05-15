package sweetgirl.croissant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.external.aws.S3Service;

@SpringBootTest
class CroissantApplicationTests {

	@MockitoBean
	S3Service s3Service;

	@Test
	void contextLoads() {
		assertThat(true).isTrue();
	}

}
