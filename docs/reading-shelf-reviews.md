# 내 서재와 책별 후기 v1

계정 프로필 확장 브랜치에서는 인증이 기본값이며, 아래 사용자 ID 데모 설명은
`APP_AUTH_MODE=demo`의 기존 미등록 사용자에만 적용된다.
등록 계정의 서재와 후기는 인증 토큰과 소유권을 검증한다.
[계정·준비도 API](account-reader-profiles.md)를 함께 참고한다.

멘토링에서 제안한 독서 기록과 가벼운 커뮤니티의 첫 단계다. 등록된 도서를 서재에 담고 읽기 상태/메모를 관리하며, 책별 공개 한줄평과 체감 난이도를 남긴다. 후기 수치를 ML 난이도나 추천 점수에 자동 반영하지 않는다.

## API

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/users/{userId}/shelf` | 서재 목록, page=0, size=20 (최대 100), 선택 status 필터 |
| POST | `/api/users/{userId}/shelf/{bookId}` | 읽고 싶어요 상태로 담기. 이미 있으면 상태/메모 유지하고 목록 상단으로 이동 |
| PUT | `/api/users/{userId}/shelf/{bookId}` | 읽기 상태와 메모 전체 저장/교체 |
| DELETE | `/api/users/{userId}/shelf/{bookId}` | 서재에서 제외, 204. 공개 후기 유지 |
| PUT | `/api/users/{userId}/reviews/{bookId}` | 책별 사용자 후기 저장/교체 |
| DELETE | `/api/users/{userId}/reviews/{bookId}` | 사용자 후기 삭제, 204 |
| GET | `/api/books/{bookId}/reviews` | 공개 후기 목록, page=0, size=20 (최대 100) |

상태는 `WANT_TO_READ / READING / FINISHED`, 체감 난이도는 `EASY / APPROPRIATE / HARD`다. 서재 저장 body는 `{"status":"READING","note":"2장까지 읽음"}`, 후기 저장 body는 `{"difficulty":"APPROPRIATE","text":"벡터의 기초를 알고 읽으면 좋아요"}`다. 메모는 빈 문자열 허용, 최대 1,000자. 후기는 공백만 있는 입력 금지, 최대 300자. PUT은 patch가 아니므로 필드를 모두 보낸다.

서재 목록은 `bookId,title,author,status,note,updatedAt,review`를 반환한다. `review`는 자신의 후기 `{difficulty,text}` 또는 null이다. 공개 목록은 `authorLabel,difficulty,text,updatedAt`만 포함하며 메모/서재 상태/사용자 실명은 반환하지 않는다. 표시 이름은 데모 번호 기반 `독자 N`이다. 목록 응답 공통 형식은 `content,page,size,totalElements,totalPages`, 정렬은 수정 시각 내림차순 + ID 내림차순이다.

존재하지 않는 사용자/책은 404, 잘못된 enum/길이/JSON/페이지 값은 400이다. 기존 추천 feedback과 별개의 테이블이며, 추천을 받지 않은 책에도 후기를 남길 수 있다. 서재를 지워도 후기는 남고, 책을 다시 담으면 자신의 후기를 다시 수정할 수 있다. 삭제/저장은 지정된 사용자와 도서 조합에만 적용한다. DB 복합 PK와 upsert로 중복 저장을 방지한다.

## 사용자 식별과 배포 범위

기본 실행은 Bearer 인증으로 계정을 확인하고 URL의 userId가 로그인 계정과 일치하는지 검사한다. 서재 메모는 소유자만 조회·수정하며 공개 후기 응답에는 포함하지 않는다. 명시적 `APP_AUTH_MODE=demo`에서만 미등록 데모 사용자 ID를 익명으로 사용할 수 있고, 등록 계정에는 이 예외가 적용되지 않는다. 프론트는 로그인과 Authorization 전달을 연결해야 한다. 공개 서비스 배포 전 HTTPS, 신고/운영 정책과 분산 rate limit이 필요하며, 이번 변경은 운영 커뮤니티 출시를 의미하지 않는다.

## 확인 범위

PostgreSQL/Testcontainers HTTP 통합 테스트로 생성/교체/삭제, 중복 담기 시 메모 보존, 사용자별 저장 키, 메모의 공개 응답 제외, 페이지/필터, 입력 검증을 확인한다. 기존 데이터는 새 테이블 추가 migration으로 보존한다. 좋아요, 댓글, 미션, 독서일자 이력/통계는 이번 단계에 포함하지 않는다.
