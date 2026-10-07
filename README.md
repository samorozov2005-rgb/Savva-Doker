# TaskAPI — Docker + Spring Boot

Учебный REST API задач на Java 21, Spring Boot, Spring Security, JWT и PostgreSQL. Проект демонстрирует multi-stage Docker build, Docker Compose, healthcheck, внутреннюю сеть Compose и сохранение данных в named volume.

## Требования

- Docker
- Docker Compose (`docker compose`)

Java и Maven на компьютере не нужны для обычного запуска: приложение собирается внутри Docker builder stage с помощью Maven Wrapper.

## Первый запуск

Создайте локальный файл с переменными окружения:

```bash
cp .env.example .env
```

Замените `change_me` в `.env` на свои значения. `JWT_SECRET` должен быть не короче 32 байт. Затем запустите:

```bash
docker compose up --build
```

API будет доступен по адресу `http://localhost:8080`. PostgreSQL наружу не публикуется: он нужен только приложению во внутренней сети Compose.

## Управление контейнерами

Запуск в фоне:

```bash
docker compose up --build -d
```

Остановка с удалением контейнеров и сети (данные остаются):

```bash
docker compose down
```

Полное удаление вместе с named volume и данными БД:

```bash
docker compose down -v
```

Логи приложения и статус сервисов:

```bash
docker compose logs -f app
docker compose ps
```

Открыть PostgreSQL shell и вывести таблицы:

```bash
docker compose exec postgres psql -U taskuser -d taskdb
```

```sql
\dt
```

Если в `.env` заданы другие пользователь или БД, подставьте их в команду. Без ручной подстановки:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Ресурсы контейнеров и конфигурация сети приложения:

```bash
docker stats
docker inspect taskapi-app --format '{{json .NetworkSettings.Networks}}'
```

Выход из непрерывного `docker stats` — `Ctrl+C`.

## Примеры API

### 1. Регистрация

```bash
curl -i -X POST http://localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"password123"}'
```

Регистрация возвращает JWT и HTTP 201. Повторная регистрация того же имени возвращает HTTP 409.

### 2. Вход и сохранение JWT

С `jq` удобно сохранить токен в переменную:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"password123"}' | jq -r '.token')
```

Без `jq` скопируйте значение поля `token` из ответа и выполните:

```bash
TOKEN='сюда_вставить_JWT'
```

JWT передаётся в каждый защищённый запрос в заголовке `Authorization` после слова `Bearer` и пробела: `Authorization: Bearer <JWT>`.

### 3. Создание задач

```bash
curl -i -X POST http://localhost:8080/tasks \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Изучить Docker","description":"Разобрать multi-stage build","completed":false}'
```

### 4. Получение задач

Все задачи (для любого авторизованного пользователя):

```bash
curl -s http://localhost:8080/tasks \
  -H "Authorization: Bearer $TOKEN"
```

Только задачи текущего пользователя:

```bash
curl -s http://localhost:8080/tasks/my \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Удаление задачи

Подставьте реальный `id` из ответа на создание или GET. Удалять задачу может только её владелец:

```bash
curl -i -X DELETE http://localhost:8080/tasks/1 \
  -H "Authorization: Bearer $TOKEN"
```

## Проверка persistence

1. Зарегистрируйтесь, войдите и создайте несколько задач командами выше.
2. Выполните `docker compose down`, затем `docker compose up -d`.
3. Снова войдите с теми же данными и вызовите `GET /tasks/my`: пользователь и задачи сохранились в `postgres_data`.
4. Выполните `docker compose down -v`, затем `docker compose up -d`.
5. Старый вход теперь не сработает, потому что volume вместе с данными удалён.

`docker compose down` удаляет контейнеры, поэтому JWT можно получить заново. Сам JWT не хранится в БД; пользователь и задачи хранятся в PostgreSQL.

## Проверка отказа PostgreSQL

```bash
docker compose stop postgres
curl -i http://localhost:8080/tasks -H "Authorization: Bearer $TOKEN"
docker compose logs app
docker compose start postgres
```

Endpoint обращается к БД и во время остановки PostgreSQL завершится ошибкой соединения (обычно HTTP 500 после таймаута). В логах приложения будет ошибка JDBC/connection refused. После `start` дождитесь healthy-статуса через `docker compose ps` и повторите запрос.

## Локальная Maven-проверка

Для неё нужны Java 21 и доступный PostgreSQL только при ручном запуске приложения. Тесты используют in-memory H2:

```bash
./mvnw clean test
./mvnw clean package
```

Локальный запуск API с PostgreSQL на `localhost:5432`:

```bash
./mvnw spring-boot:run
```

Параметры можно переопределить через `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` и `JWT_SECRET`.

## Как устроен проект

- `Dockerfile`: JDK + Maven Wrapper используются только в builder stage; в runtime остаются JRE и готовый JAR.
- `docker-compose.yml`: поднимает `app` и `postgres`, ждёт готовности БД по healthcheck.
- `application-docker.properties`: получает подключение к БД и JWT secret только из ENV.
- `SecurityConfig` и `JwtAuthenticationFilter`: stateless-проверка JWT для `/tasks/**`.
- `AuthController`: регистрация с BCrypt и вход.
- `TaskController`: создание, просмотр и удаление собственных задач.
- `postgres_data`: named volume с каталогом данных PostgreSQL.

## Вопросы для защиты

### 1. Чем image отличается от container?

Image — неизменяемый шаблон из слоёв с приложением и его окружением. Container — запущенный из image изолированный процесс с собственным изменяемым слоем. Из одного image можно запустить несколько containers.

### 2. Зачем multi-stage build?

Для компиляции нужны Maven и JDK, но во время выполнения нужен только JRE и JAR. Builder stage собирает приложение, а runtime stage получает только результат. Итоговый image меньше, содержит меньше лишних инструментов и имеет меньшую поверхность атаки.

### 3. Почему PostgreSQL доступен как `postgres`, а не localhost?

Compose создаёт общую сеть и встроенный DNS, который разрешает имя сервиса `postgres` в IP его контейнера. Внутри контейнера приложения `localhost` означает сам контейнер приложения, а не PostgreSQL и не компьютер пользователя.

### 4. Что делает `depends_on` + `service_healthy`?

Обычный запуск контейнера ещё не означает, что PostgreSQL закончил инициализацию и принимает соединения. Условие `service_healthy` запускает приложение после успешного healthcheck БД. Это упорядочивает первоначальный старт, но не заменяет обработку сбоев после запуска.

### 5. Что делает healthcheck?

Команда `pg_isready` регулярно проверяет, готов ли PostgreSQL принимать подключения для указанных пользователя и БД. По коду возврата Docker присваивает контейнеру статус `healthy` или `unhealthy`.

### 6. Что происходит при `docker compose down`?

Compose останавливает и удаляет контейнеры и созданную сеть. Named volume по умолчанию остаётся, поэтому файлы PostgreSQL и данные сохраняются.

### 7. Что происходит при `docker compose down -v`?

Дополнительно удаляются volumes Compose-проекта. Каталог данных PostgreSQL исчезает, и при следующем запуске создаётся пустая БД.

### 8. Зачем `.env`?

Он отделяет изменяемую конфигурацию и секреты от `docker-compose.yml`. Compose автоматически подставляет его переменные в `${...}`.

### 9. Почему `.env` нельзя коммитить?

В нём находятся пароли и JWT secret. Коммит может раскрыть секреты через историю Git даже после последующего удаления файла.

### 10. Зачем `.env.example`?

Он документирует обязательные имена переменных и безопасные примеры без настоящих секретов, чтобы другой разработчик мог создать свой `.env`.

### 11. Зачем non-root user?

Процесс Java не нуждается в root-правах. Если приложение будет скомпрометировано, пользователь `appuser` ограничит возможности атакующего внутри контейнера.

### 12. Что показывают Docker cache и порядок `COPY`?

Сначала копируются `pom.xml`, Maven Wrapper и скачиваются зависимости. Исходники копируются позже. Изменение Java-кода поэтому не инвалидирует тяжёлый слой зависимостей; Docker переиспользует cache, пока `pom.xml` не изменился.

### 13. Что делают `EXPOSE` и `ports`?

`EXPOSE 8080` документирует порт приложения внутри image, но сам его не публикует. `ports: "8080:8080"` в Compose связывает порт 8080 компьютера с портом 8080 контейнера.

### 14. Где хранятся пароли и зачем BCrypt?

В таблице `users` хранится необратимый BCrypt hash с солью, а не открытый пароль. При входе Spring Security сравнивает введённый пароль с hash через `PasswordEncoder`.

### 15. Зачем JWT?

После входа сервер подписывает токен секретом. Клиент передаёт его в заголовке `Authorization`; сервер проверяет подпись и срок действия без HTTP-сессии. `JWT_SECRET` приходит из окружения, а не зашит в Java-код.
