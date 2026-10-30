# syntax=docker/dockerfile:1
# 빌드는 컨테이너 안에서 한다. 홈서버에 JDK와 Node를 설치하지 않는다.
# 사용: docker build -f docker/Dockerfile --target build .     # 화면 검사 + gradle build
#       docker build -f docker/Dockerfile -t homeview-app .     # 실행 이미지

FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ ./
RUN npm run typecheck && npm run lint && npm run test && npm run build

FROM eclipse-temurin:25-jdk AS source
WORKDIR /workspace
COPY . .
COPY --from=web /web/dist src/main/resources/static

FROM source AS build
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain build \
    && cp build/libs/app.jar /app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
