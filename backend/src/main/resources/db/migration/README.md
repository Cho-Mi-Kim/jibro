# 운영 마이그레이션

실제 도메인 모델 합의 후 `V1__init.sql`부터 추가합니다. 테이블 생성은 Flyway로 관리하고 JPA는 `validate`를 유지합니다.
호환성 검증용 테이블은 test 리소스와 `jibro_test` DB에만 생성합니다.
