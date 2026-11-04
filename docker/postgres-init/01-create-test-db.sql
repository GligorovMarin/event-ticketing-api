-- 01-create-test-db.sql
--
-- Run by the Postgres container once, when its data volume is first created (see docker-compose.yml).
--
-- Integration tests insert and delete rows. If they used the development database they would wipe
-- the data you click around with in Swagger, so they get their own database. The "test" Spring
-- profile (src/main/resources/application-test.yml) points at it.

CREATE DATABASE ticketing_test;
