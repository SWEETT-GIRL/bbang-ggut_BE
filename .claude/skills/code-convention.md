---
name: code-convention
description: 코드 컨벤션 가이드. 코드 작성, Entity/Service/Controller/DTO 구현 시 참조
---

# 코드 컨벤션

## 네이밍 규칙

| 대상 | 규칙 | 예시 |
|------|------|------|
| Class | UpperCamelCase | `UserCommandService` |
| Method / Field | lowerCamelCase | `getUserById` |
| Constant | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| Package | lowercase | `sweetgirl.croissant` |
| Table / Column | lower_snake_case | `user_id` |

---

## 코드 품질 기준

| 구분 | 권장 | 최대 |
|------|------|------|
| 파일 | 200-400줄 | 800줄 |
| 함수 | 20-30줄 | 50줄 |
| 중첩 | 2-3단계 | 4단계 |

---

## Java 스타일

```java
// ✅ 생성자/메서드 파라미터가 많을 경우 줄바꿈 사용
public UserCreateRequest(
        String name,
        String email
) {
    this.name = name;
    this.email = email;
}

// ✅ try-catch는 필요한 경우에만 사용하고, 예외는 명확하게 처리
try {
    riskyOperation();
} catch (ExternalApiException exception) {
    log.warn("External API call failed", exception);
    throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
}

// ✅ null 직접 반환 지양, Optional 또는 예외 처리 사용
User user = userRepository.findById(id)
        .orElseThrow(() -> new UserException.NotFound(id));

// ✅ Objects.requireNonNull 사용 가능
User user = Objects.requireNonNull(
        userRepository.findById(id).orElse(null),
        "User not found: " + id
);

// ✅ 커스텀 예외 사용
User user = userRepository.findById(id)
        .orElseThrow(() -> new UserException.NotFound(id));

// ✅ 변수명, 메서드명, 클래스명은 의미가 드러나도록 작성
UserRepository userRepository;
UserService userService;
UserCreateRequest userCreateRequest;
```

- [ ] `null` 직접 반환 지양
- [ ] 불필요한 `try-catch` 사용 금지
- [ ] `printStackTrace()` 사용 금지
- [ ] 약어 사용 금지 (`svc`, `repo`, `req`, `res` 등)
- [ ] 의미 없는 변수명 사용 금지 (`data`, `temp`, `obj`, `item` 등)

---

## Entity 작성

```java
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity extends BaseEntity {

    private String email;

    private String name;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    private UserEntity(
            String email,
            String name,
            UserStatus status
    ) {
        this.email = email;
        this.name = name;
        this.status = status;
    }

    public static UserEntity create(
            String email,
            String name
    ) {
        return new UserEntity(
                email,
                name,
                UserStatus.ACTIVE
        );
    }

    public void update(
            String email,
            String name
    ) {
        if (email != null) {
            this.email = email;
        }

        if (name != null) {
            this.name = name;
        }
    }
}
```

- [ ] `@Setter` 사용 금지
- [ ] 기본 생성자는 `protected`로 제한
- [ ] 외부에서 `new Entity(...)`로 직접 생성하지 않고 정적 팩토리 메서드 사용
- [ ] Enum 필드에는 `@Enumerated(EnumType.STRING)` 사용
- [ ] 상태 변경은 Setter가 아니라 의미 있는 메서드로 처리

---

## Enum 작성

```java
@Getter
@RequiredArgsConstructor
public enum UserStatus {

    ACTIVE("활성"),
    INACTIVE("비활성"),
    SUSPENDED("정지"),
    ;

    private final String description;
}
```

- [ ] Enum 클래스명은 `PascalCase`로 작성
- [ ] Enum 상수는 `UPPER_SNAKE_CASE`로 작성
- [ ] 설명이 필요한 경우 `description` 필드 사용
- [ ] JPA Entity에서 Enum 사용 시 `@Enumerated(EnumType.STRING)` 필수
- [ ] `EnumType.ORDINAL` 사용 금지

---

## Exception 작성

```java
public class UserException extends CustomException {

    private UserException(
            HttpStatus status,
            String message
    ) {
        super(status, message);
    }

    public static class NotFound extends UserException {

        public NotFound(Long id) {
            super(
                    HttpStatus.NOT_FOUND,
                    "사용자를 찾을 수 없습니다: " + id
            );
        }
    }

    public static class EmailAlreadyExists extends UserException {

        public EmailAlreadyExists(String email) {
            super(
                    HttpStatus.CONFLICT,
                    "이미 존재하는 이메일입니다: " + email
            );
        }
    }
}
```

- [ ] 도메인별 Exception 클래스 작성
- [ ] `CustomException` 상속
- [ ] 세부 예외는 내부 `static class`로 작성
- [ ] `HttpStatus`와 메시지를 명확히 지정
- [ ] 예외 응답은 `GlobalExceptionHandler`에서 공통 처리

---

## Service 작성

### CommandService (CUD)

```java
@Service
@RequiredArgsConstructor
@Transactional
public class UserCommandService {

    private final UserRepository userRepository;

    public UserResponseDto create(CreateUserRequestDto request) {
        UserEntity user = UserEntity.create(
                request.email(),
                request.name()
        );

        UserEntity savedUser = userRepository.save(user);

        return UserResponseDto.from(savedUser);
    }
}
```

### QueryService (R)

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;

    public UserResponseDto getUserById(Long id) {
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new UserException.NotFound(id));

        return UserResponseDto.from(user);
    }
}
```

- [ ] Service는 CommandService와 QueryService로 분리
- [ ] CommandService는 CUD 작업만 담당하고 `@Transactional` 사용
- [ ] QueryService는 조회 작업만 담당하고 `@Transactional(readOnly = true)` 사용
- [ ] Entity 생성은 정적 팩토리 메서드 사용
- [ ] Service에서 Entity를 그대로 반환하지 않고 Response DTO로 변환

---

## Controller 작성

```java
@Tag(name = "[Admin] 사용자 API", description = "사용자 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/users")
public class AdminUserQueryController {

    private final UserQueryService userQueryService;

    @Operation(
            summary = "사용자 목록 조회",
            description = "필터 조건에 따라 사용자 목록을 조회합니다."
    )
    @GetMapping
    public ApiResponse<Page<UserResponse>> getUsers(
            @ModelAttribute UserSearchRequest request,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<UserResponse> response = userQueryService.getUsers(
                        request.toDto(),
                        pageable
                )
                .map(UserResponse::from);

        return ApiResponse.ok(response);
    }
}
```

- [ ] `@Tag`의 `name`에는 `[Admin]` 또는 `[Client]`를 붙여 API 대상 구분
- [ ] `@Operation`의 `summary`, `description`은 한글로 작성
- [ ] Controller에서는 비즈니스 로직 작성 금지
- [ ] 흐름은 `Request → toDto() → Service → ResponseDto → Response.from()` 형태로 작성
- [ ] 응답은 `ApiResponse.ok()`로 래핑

---

## DTO 작성

### presentation 계층

```java
// Request
@Schema(description = "사용자 생성 요청")
public record CreateUserRequest(

        @Schema(description = "이메일", example = "user@example.com")
        String email

) {
    public CreateUserRequestDto toDto() {
        return new CreateUserRequestDto(email);
    }
}

// Response
public record UserResponse(
        Long id,
        String email
) {
    public static UserResponse from(UserResponseDto dto) {
        return new UserResponse(
                dto.id(),
                dto.email()
        );
    }
}
```

### application 계층

```java
// RequestDto
public record CreateUserRequestDto(
        String email
) {
}

// ResponseDto
public record UserResponseDto(
        Long id,
        String email
) {
    public static UserResponseDto from(UserEntity entity) {
        return new UserResponseDto(
                entity.getId(),
                entity.getEmail()
        );
    }
}
```

- [ ] DTO는 기본적으로 `record`로 작성
- [ ] presentation 계층에는 `Request`, `Response` 작성
- [ ] application 계층에는 `RequestDto`, `ResponseDto` 작성
- [ ] Request에는 `toDto()` 작성
- [ ] Response와 ResponseDto에는 `from()` 작성

---

## 데이터베이스

**테이블:**
- 테이블명 복수형 (`users`, `orders`)
- PK는 `id` (bigint auto_increment)
- Boolean은 `boolean` 타입 (`use_yn` char 금지)

**Audit 컬럼 (필수):**

```sql
created_at DATETIME(6) NOT NULL,
updated_at DATETIME(6) NOT NULL
```

- [ ] 테이블명은 복수형 사용 (`users`, `orders`)
- [ ] 테이블명과 컬럼명은 `lower_snake_case` 사용
- [ ] PK 컬럼명은 `id`, 타입은 `BIGINT AUTO_INCREMENT` 사용
- [ ] Boolean 값은 `BOOLEAN` 타입 사용 (`use_yn` 금지)
- [ ] 모든 테이블에 `created_at`, `updated_at` 필수

---

## Query 컨벤션

단순 조회는 Spring Data JPA 메서드 쿼리를 사용.
조건이 2개 이상이거나 동적 검색 조건이 필요한 경우 QueryDSL을 사용.
@Query(JPQL)는 복잡도가 낮고 QueryDSL까지 필요 없는 경우에만 제한적으로 사용.

- [ ] 단순 조회는 Spring Data JPA 메서드 쿼리 사용
- [ ] 조건이 2개 이상이거나 동적 조건이 필요한 경우 QueryDSL 사용
- [ ] `@Query`는 QueryDSL까지 필요 없는 단순 JPQL에만 제한적으로 사용
- [ ] 목록 조회, 검색 조회는 필요한 필드만 조회하는 Projection 사용 가능
- [ ] 수정, 삭제, 상태 변경이 필요한 기능은 Projection이 아니라 Entity 조회
