package sweetgirl.croissant;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CoreTestApplication {
	// core 모듈 테스트(@DataJpaTest·@SpringBootTest)가 찾는 설정 클래스.
	// 실행 클래스(CroissantApplication)는 api 모듈에 있어 core 테스트 클래스패스에는 없다.
}
