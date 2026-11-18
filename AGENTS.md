# FishingStory API 작업 지침

Codex와 Claude Code 공통 진입점이다. **작업 전에 [상위 AGENTS.md](../AGENTS.md)를 실제로 읽고 공통 규칙을 적용한다.** 이 저장소에서 시작하면 Git 루트 밖의 공통 지침이 자동으로 로드되지 않을 수 있다. 이미 현재 대화에서 읽었다면 반복하지 않는다.

- 최신 결정·실행 여부는 [MEMORY.md](../MEMORY.md), 범위·보류 항목은 [개발 정리](../docs/Local_MVP_Completion.md)를 확인한다. 과거 테스트 통과를 이후 수정의 검증 결과로 사용하지 않는다.
- API Git·Gradle·Compose 명령은 이 디렉터리에서 수행한다. 준비·사용자 실행 명령은 [README.md](README.md), 구조·API 규칙은 [Architecture](../docs/02_Architecture.md), 데이터 원칙은 [Database](../docs/03_Database.md)를 따른다.
- 소스는 `src/main/java/kr/fishingstory/`, 테스트는 `src/test/java/kr/fishingstory/`, 실제 스키마 원본은 `src/main/resources/db/migration/`다. 대상 기능의 상세 문서와 가장 가까운 기존 구현을 먼저 확인한다.
- 공통 규칙 §5의 실행 범위를 적용한다. Testcontainers 기반 Gradle `test`·`build`·`compileJava`는 에이전트가 바로 실행하고, 개발 DB를 쓰는 `bootRun`·Compose 서버와 그 서버에 대한 HTTP 검증은 사용자 요청 시에만 실행한다.
- `CLAUDE.md`는 이 파일을 가져오는 연결 파일이다. 공통 규칙은 상위 AGENTS.md, API에만 필요한 추가 규칙은 이 파일에서 관리한다. 상위 문서가 없는 별도 checkout에서는 누락을 알리고 로컬 지침을 적용한다.

## 매거진

API magazine/·Flyway V10의 수신/공개/중단·전용 이미지 계약은 [Magazines_Studio](../docs/Magazines_Studio.md)를 따른다. 최신 테스트 범위와 실제 DB/R2 미검증 상태는 [개발 범위·검증 표](../docs/Local_MVP_Completion.md)를 따른다. 최신 Migration 파일은 V16(조황 사진 예약 키, 격리 검증 통과·개발/운영 DB 미적용)이며 직전 V15는 탈퇴 취소 시 Access 세대를 올린다.

## 포인트·물때날씨

등록 포인트·지역/권역·환경 연결의 목표/미결정은 [포인트 공통 계약](../docs/points/Points_Weather_Tides.md)을 따른다. 현재 대표 지역의 환경 조회와 [등록 포인트 V14·조회 API](../docs/points/Registered_Points_API.md)를 구분한다. 포인트/지역/권역/별칭은 [격리 테스트·build·HTTP 검증](../docs/reviews/Points_Validation_2026-10-31.md)을 통과했으며 즐겨찾기·조황 좌표·포인트 환경 연결은 아직 없다. dev 더미는 명시 설정에서만 생성/조회하고 prod는 차단한다. 외부 준비는 [사용자 직접 작업 목록](../docs/points/User_Action_Checklist.md)에 누적한다.

conditions/의 ACTIVE 회원 조회·선택 서버 키·공유 캐시·외부 호출 제한·한국 날짜/자료 상태는 [물때날씨 계약](../docs/Fishing_Conditions.md)을 따른다. 실제 외부 API 호출도 사용자 명시 요청 전에는 실행하지 않으며 테스트는 공급자 대역/키 미설정으로 작성한다.
