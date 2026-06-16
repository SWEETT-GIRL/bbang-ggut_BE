---
name: test-guide
description: 테스트 작성 가이드. 테스트 코드 생성/수정 시 참조
---

# 테스트 가이드

## 테스트 프레임워크

- **JUnit5** — `@Test`, `@DisplayName`, `@Nested`
- **Mockito** — `@Mock` / `@InjectMocks`, BDD 스타일(`given` / `then`)
- **AssertJ** — `assertThat`, `assertThatThrownBy`
- 계층별: 서비스 = 단위 테스트, 컨트롤러 = `@WebMvcTest`, 리포지토리 = `@DataJpaTest`

---

## 테스트 대상 체크리스트

**필수:**
- [ ] Service — 비즈니스 로직 (최우선)
- [ ] Controller — HTTP 레이어 (`@WebMvcTest`)
- [ ] Custom Repository — 복잡한 쿼리 (`@DataJpaTest`)

**생략 가능:**
- record DTO 변환 (`toDto`, `from`)
- Entity 정적 팩토리 (`create`)
- 단순 Getter

---

## Service 테스트 (Mockito)

```java
@ExtendWith(MockitoExtension.class)
class UserCommandServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserCommandService userCommandService;

    @Nested
    @DisplayName("사용자 생성")
    class Create {

        @Test
        @DisplayName("이메일이 중복되지 않으면 사용자가 생성된다")
        void success() {
            // given
            CreateUserRequestDto request = new CreateUserRequestDto("test@example.com");
            UserEntity user = UserFixture.user(1L);
            given(userRepository.existsByEmail(any())).willReturn(false);
            given(userRepository.save(any())).willReturn(user);

            // when
            UserResponseDto result = userCommandService.create(request);

            // then
            assertThat(result.id()).isEqualTo(1L);
            then(userRepository).should(times(1)).save(any());
        }

        @Test
        @DisplayName("이메일이 이미 존재하면 예외가 발생한다")
        void emailAlreadyExists() {
            CreateUserRequestDto request = new CreateUserRequestDto("test@example.com");
            given(userRepository.existsByEmail(any())).willReturn(true);

            assertThatThrownBy(() -> userCommandService.create(request))
                    .isInstanceOf(UserException.EmailAlreadyExists.class);
        }
    }
}
```

> 신규 서비스 메서드는 **성공 케이스 + 실패(예외) 케이스 최소 1개씩**.

---

## Mockito 패턴 (BDD 스타일)

```java
// 반환값 설정
given(userRepository.findById(any())).willReturn(Optional.of(user));
given(userRepository.existsByEmail(any())).willReturn(false);

// void 메서드
willDoNothing().given(userRepository).deleteById(any());

// 예외
given(userRepository.findById(any())).willThrow(new UserException.NotFound(1L));

// 호출 검증
then(userRepository).should(times(1)).save(any());
then(userRepository).shouldHaveNoMoreInteractions();
```

---

## AssertJ Assertions

```java
// 동등 비교
assertThat(result.id()).isEqualTo(1L);

// null
assertThat(result).isNotNull();

// 컬렉션
assertThat(users).hasSize(3);
assertThat(users).extracting(UserResponse::email).contains("test@example.com");

// 예외 검증
assertThatThrownBy(() -> userQueryService.getUserById(999L))
        .isInstanceOf(UserException.NotFound.class)
        .hasMessageContaining("사용자를 찾을 수 없습니다");
```

---

## 테스트 데이터 생성 (Fixture)

Entity는 기본 생성자가 `protected`이고 정적 팩토리로만 생성되므로, 테스트 데이터는 **Fixture 클래스**로 만든다. `id`처럼 영속 후 채워지는 값은 `ReflectionTestUtils`로 주입한다.

### 파일 위치 및 네이밍
- **위치**: `test/.../{도메인}/` (예: `test/.../user/`)
- **파일명**: `{도메인}Fixture.java` (예: `UserFixture.java`)

```java
public class UserFixture {

    public static UserEntity user(Long id) {
        UserEntity user = UserEntity.create(
                "test@example.com",
                "테스트유저"
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
```

---

## Controller 테스트 (`@WebMvcTest`)

```java
@WebMvcTest(UserQueryController.class)
class UserQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserQueryService userQueryService;

    @Test
    @DisplayName("GET /v1/users/{id} — 200과 사용자 정보를 반환한다")
    void getUserById() throws Exception {
        given(userQueryService.getUserById(1L))
                .willReturn(new UserResponseDto(1L, "test@example.com"));

        mockMvc.perform(get("/v1/users/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1L));
    }
}
```

---

## 테스트 이름

`@DisplayName` 한글 또는 `메서드_상황_기대결과` — **팀 내 하나로 통일**(혼용 금지).
