# Homeview 골격 + CI/CD 구현 계획 (계획 1/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빈 토폴로지를 반환하는 Spring Boot 앱과 그 응답을 표시하는 React 화면을 만들고, 홈서버 self-hosted runner로 CI(PR 검증)와 CD(앱·프록시 배포)를 끝까지 돌린다.

**Architecture:** Gradle 단일 모듈(Kotlin, Spring Boot)과 `web/`(Vite, React, TypeScript)을 한 저장소에 둔다. `docker/Dockerfile`이 화면을 먼저 빌드하고, 그 결과를 Spring Boot 정적 리소스로 넣어 jar 하나를 만든다. 배포는 `deploy/compose.yml`로 앱과 `docker-socket-proxy`를 올린다. 러너는 GitHub 조직 단위로 등록해 STOCK_GUIDE와 공유한다.

**Tech Stack:** JDK 25, Kotlin 2.3.21, Spring Boot 4.1.1, Gradle 9.8.2, kotlinter 5.7.0, Node 24(Docker), React 19.3.1, Vite 8.3.5, Vitest 5.0.5, TypeScript 6.0.3, ESLint 10.13.0, `tecnativa/docker-socket-proxy:v0.5.0`, GitHub Actions self-hosted runner(라벨 `homeserver`).

**Spec:** `docs/superpowers/specs/2026-10-22-homeview-design.md` (SPEC-HV-001)

**후속 계획:**
- 계획 2: 출처 연결 — `DockerSource`, `MetricsSource`, `HostSource`, `TopologyAssembler`, 5초 캐시, 수집기 설치.
- 계획 3: 화면 — 위쪽 바, 섹션 격자 그래프(5.2~5.4), 상세 패널, 15초 갱신.

## Global Constraints

- 브랜치: `feat/hv-skeleton-ci-cd`. 커밋 메시지는 한국어 Conventional Commits, 제목 끝에 `(SPEC-HV-001)`, 본문에 `Refs: SPEC-HV-001`. 끝 줄은 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- git 상태 변경(commit, branch, push, PR)은 `git-master` 에이전트에 맡긴다. git-master가 `[승인 요청]`을 반환하면 사용자에게 보여 주고, 사용자가 승인하면 메인 세션이 그 명령을 그대로 실행한다.
- PR merge는 CI가 통과한 뒤 사용자의 확인을 받고 진행한다.
- 버전: Kotlin `2.3.21`, Spring Boot `4.1.1`, `io.spring.dependency-management` `1.1.7`, kotlinter `5.7.0`, Gradle `9.8.2`, JDK toolchain `25`.
- `gradle/libs.versions.toml`을 만들지 않는다. 플러그인 버전은 `build.gradle.kts`의 plugins 블록에 둔다.
- 패키지 루트: `com.bombo.homeview`. group `com.bombo.homeview`, version `0.0.1-SNAPSHOT`.
- Kotlin 수정 후 `./gradlew formatKotlin` → `./gradlew lintKotlin` 순서로 실행한다. 규칙을 끄거나 suppress하지 않는다.
- 테스트: 영문 메서드명 + 한글 `@DisplayName`, `@Nested`는 `inner class`, PER_CLASS 라이프사이클(`junit-platform.properties`).
- 시간은 `Clock` 빈을 주입해서 쓴다. `OffsetDateTime.now()`를 인자 없이 호출하지 않는다.
- TypeScript는 `6.0.3`으로 고정한다. `typescript-eslint` 8.72.1은 `typescript <6.1.0`만 지원한다.
- React는 함수 컴포넌트와 hooks만 쓴다.
- 워크플로에 `secrets`를 쓰지 않는다. 모든 워크플로에 `permissions: contents: read`를 둔다.
- compose 파일과 워크플로에 비밀값을 넣지 않는다. 이 계획에는 비밀값이 없다.
- 홈서버의 `~/monitoring` 스택과 STOCK_GUIDE 컨테이너를 건드리지 않는다.
- 로컬 Docker 데몬은 꺼져 있다. Docker 검증은 홈서버(`ssh homeserver`)에서 하거나 CI 결과로 확인한다.

## 시작 전 확인

- [ ] `~/Documents/0000_CLAUDE_WORKSPACE`의 `feat/git-master-approval-flow` 브랜치가 main에 merge되어 있는지 확인한다. `~/.claude/agents/git-master.md`가 이 저장소를 가리키므로, merge 전이면 저장소가 main으로 돌아갈 때 git-master가 옛 승인 절차로 동작한다.

```bash
git -C ~/Documents/0000_CLAUDE_WORKSPACE log --oneline -1 origin/main -- agents/git-master.md
```

Expected: `feat(agents): git-master C등급 승인 흐름을 메인 세션 실행 방식으로 변경` 커밋이 보인다. 보이지 않으면 실행을 멈추고 사용자에게 알린다.

## File Structure

| 파일 | 책임 |
|------|------|
| `.gitignore` | 빌드 산출물, IDE 파일, `node_modules` 제외 |
| `.editorconfig` | ktlint 규칙. `~/Documents/0000_CLAUDE_WORKSPACE/templates/kotlin/.editorconfig` 복사본 |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Gradle 9.8.2 래퍼 |
| `settings.gradle.kts`, `build.gradle.kts` | 단일 모듈 빌드 설정 |
| `src/main/kotlin/com/bombo/homeview/HomeviewApplication.kt` | 앱 진입점 |
| `src/main/kotlin/com/bombo/homeview/ClockConfig.kt` | `Clock` 빈 (Asia/Seoul) |
| `src/main/kotlin/com/bombo/homeview/topology/Topology.kt` | 응답 모델 (SPEC 4.1~4.5) |
| `src/main/kotlin/com/bombo/homeview/topology/TopologyController.kt` | `GET /api/topology`. 이 계획에서는 빈 토폴로지를 반환한다 |
| `src/main/resources/application.yaml` | 포트, actuator probe |
| `src/test/kotlin/com/bombo/homeview/topology/TopologyControllerTest.kt` | 응답 형태 테스트 |
| `src/test/resources/junit-platform.properties` | PER_CLASS 라이프사이클 |
| `web/package.json`, `web/package-lock.json` | 화면 의존성과 스크립트 |
| `web/tsconfig.json`, `web/vite.config.ts`, `web/eslint.config.js` | 화면 빌드·테스트·린트 설정 |
| `web/index.html`, `web/src/main.tsx`, `web/src/App.tsx` | 화면 진입점. 출처 상태만 표시한다 |
| `web/src/App.test.tsx` | 화면 테스트 |
| `docker/Dockerfile` | `web` → `source` → `build` → `runtime` 스테이지 |
| `.dockerignore` | 빌드 컨텍스트 축소 |
| `deploy/compose.yml` | 앱과 프록시 실행 정의 |
| `.github/workflows/ci.yml` | main 대상 PR 검증 |
| `.github/workflows/cd.yml` | 서버를 골라 수동 배포 |

---

### Task 0: GitHub 조직 이전과 러너 재등록 (사용자 + 메인 세션)

이 작업은 코드 변경이 없다. Task 7 이후의 CI와 CD는 이 작업이 끝나야 동작한다. Task 1~6은 이 작업과 병행할 수 있다.

**Files:** 없음

**Interfaces:**
- Produces: 조직 이름 `ORG`(사용자가 정함). 저장소 `ORG/STOCK_GUIDE`, `ORG/homeview`. 조직 러너 `bombo-server`(라벨 `homeserver`), Default 러너 그룹이 public 저장소를 허용함.

- [ ] **Step 1: (사용자) 조직 생성**

https://github.com/account/organizations/new 에서 Free 플랜 조직을 만든다. 이후 단계에서 쓸 이름을 셸 변수로 둔다.

```bash
ORG=<사용자가 만든 조직 이름>
```

- [ ] **Step 2: (사용자) 저장소 두 개 이전**

각 저장소의 Settings → General → Danger Zone → Transfer ownership에서 `ORG`로 옮긴다.

- `bombo-dev/STOCK_GUIDE` (열린 PR #1은 함께 옮겨진다)
- `bombo-dev/homeview`

확인:

```bash
gh repo view "$ORG/STOCK_GUIDE" --json nameWithOwner,visibility
gh repo view "$ORG/homeview" --json nameWithOwner,visibility
```

Expected: 두 저장소가 `ORG` 아래에 보인다. homeview는 `PRIVATE`이다.

- [ ] **Step 3: (메인 세션, 사용자 승인 후) 로컬 원격 주소 변경**

기존 원격 변경은 git-master C등급이다. 사용자 승인 후 메인 세션이 실행한다.

```bash
git -C /Users/bombo/Documents/900_PROJECT/999_homeview remote set-url origin "https://github.com/$ORG/homeview.git"
git -C /Users/bombo/Documents/900_PROJECTS/001_STOCK_GUIDE remote set-url origin "https://github.com/$ORG/STOCK_GUIDE.git"
git -C /Users/bombo/Documents/900_PROJECT/999_homeview fetch origin
git -C /Users/bombo/Documents/900_PROJECTS/001_STOCK_GUIDE fetch origin
```

Expected: 두 `fetch`가 오류 없이 끝난다.

- [ ] **Step 4: gh 토큰에 조직 관리 권한 추가**

```bash
gh auth refresh -h github.com -s admin:org
```

Expected: 브라우저 인증 후 `gh auth status`의 Token scopes에 `admin:org`가 보인다.

- [ ] **Step 5: (사용자) 러너 서비스 중지와 제거**

현재 러너는 저장소 단위(`actions.runner.bombo-dev-STOCK_GUIDE.bombo-server.service`)이다. 홈서버 sudo는 비밀번호를 요구한다(2026-10-27 확인). 그래서 사용자가 자신의 터미널에서 실행한다.

```bash
ssh -t homeserver 'cd ~/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh uninstall'
```

Expected: `ssh homeserver "systemctl list-units --no-legend 'actions.runner*'"` 출력이 비어 있다.

- [ ] **Step 6: (메인 세션, 사용자 승인 후) 저장소 러너 등록 해제와 조직 러너 등록**

sudo가 필요 없는 단계이다.

```bash
REMOVE_TOKEN=$(gh api -X POST "repos/$ORG/STOCK_GUIDE/actions/runners/remove-token" --jq .token)
ssh homeserver "cd ~/actions-runner && ./config.sh remove --token $REMOVE_TOKEN"
REG_TOKEN=$(gh api -X POST "orgs/$ORG/actions/runners/registration-token" --jq .token)
ssh homeserver "cd ~/actions-runner && ./config.sh --unattended --url https://github.com/$ORG --token $REG_TOKEN --name bombo-server --labels homeserver --replace"
```

Expected: `Runner removed successfully`, 이어서 `Settings Saved.`

- [ ] **Step 6-1: (사용자) 러너 서비스 설치와 시작**

```bash
ssh -t homeserver 'cd ~/actions-runner && sudo ./svc.sh install && sudo ./svc.sh start'
```

확인 (메인 세션):

```bash
gh api "orgs/$ORG/actions/runners" --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

Expected: `{"name":"bombo-server","status":"online","labels":["self-hosted","Linux","X64","homeserver"]}`

- [ ] **Step 7: 러너 그룹이 public 저장소를 허용하도록 설정**

조직 러너 그룹은 기본값으로 public 저장소의 잡을 받지 않는다. STOCK_GUIDE는 public이고 homeview도 Task 8에서 public이 된다.

```bash
gh api -X PATCH "orgs/$ORG/actions/runner-groups/1" -F allows_public_repositories=true
gh api "orgs/$ORG/actions/runner-groups" --jq '.runner_groups[] | {id, name, visibility, allows_public_repositories}'
```

Expected: `{"id":1,"name":"Default","visibility":"all","allows_public_repositories":true}`

- [ ] **Step 8: 외부 기여자 PR 워크플로 승인 정책 설정**

```bash
gh api -X PUT "orgs/$ORG/actions/permissions/fork-pr-contributor-approval" -f approval_policy=all_external_contributors
gh api "orgs/$ORG/actions/permissions/fork-pr-contributor-approval"
```

Expected: `{"approval_policy":"all_external_contributors"}`

- [ ] **Step 9: STOCK_GUIDE로 러너 동작 확인**

```bash
gh workflow run runner-check -R "$ORG/STOCK_GUIDE" --ref SG-1/ci-cd
sleep 5; gh run list -R "$ORG/STOCK_GUIDE" -w runner-check -L 1
```

Expected: 1분 안에 상태가 `completed success`가 된다. `queued`에 머물면 Step 7을 다시 확인한다.

---

### Task 1: SPEC 개정 — 배포 절차와 호스트 파일 마운트

SPEC 7.2는 맥 빌드 → `docker save` → `ssh docker load` 절차이다. 이 계획은 러너 CD로 배포한다. 또한 수집기는 `mv`로 호스트 파일을 교체한다(2.2 결정 4). 파일 하나를 bind mount하면 컨테이너는 교체 전 파일(inode)을 계속 본다. 그래서 디렉터리를 마운트한다.

**Files:**
- Modify: `docs/superpowers/specs/2026-10-22-homeview-design.md`
- Modify: `docs/superpowers/specs/2026-10-22-homeview-design.html`

**Interfaces:**
- Produces: 앱 안의 호스트 파일 경로 `/data/host.json`. 마운트 `/var/lib/homeview:/data:ro`.

- [ ] **Step 1: 머리말 상태 변경**

md와 html에서 상태를 바꾼다.

```diff
- - 상태: 사용자 검토 대기
+ - 상태: 승인됨 (2026-10-27)
```

- [ ] **Step 2: 2.2 결정 5 수정**

```diff
- 5. 코드 저장소는 맥의 `/Users/bombo/Documents/900_PROJECT/999_homeview`이다. 홈서버에는 이미지, compose 파일, 수집기만 배포한다.
+ 5. 코드 저장소는 GitHub `<ORG>/homeview`이다. 홈서버의 러너가 저장소를 받아 이미지를 빌드하고 배포한다 (7.2 참조).
```

`<ORG>`는 Task 0 Step 1의 조직 이름으로 쓴다.

- [ ] **Step 3: 7.1 설정 값 수정**

```diff
- | 홈서버 경로 | `~/homeview` |
+ | 배포 작업 디렉터리 | 러너 작업 디렉터리. compose 프로젝트 이름으로 구분한다. |
- | 마운트 | `/var/lib/homeview/host.json:/data/host.json:ro` |
+ | 마운트 | `/var/lib/homeview:/data:ro`. 앱은 `/data/host.json`을 읽는다. |
```

7.1 표 아래에 다음 문단을 추가한다.

```markdown
앱은 호스트 파일이 아니라 디렉터리를 마운트한다. 수집기는 `mv`로 파일을 교체한다. 파일 하나만 마운트하면, 컨테이너는 교체 전 파일을 계속 읽는다.
```

- [ ] **Step 4: 7.2 이미지 배포 절차 교체**

```markdown
### 7.2 이미지 배포 절차

배포는 GitHub Actions `cd` 워크플로가 한다. 홈서버의 조직 러너(라벨 `homeserver`)가 실행한다.

1. 사용자가 `cd` 워크플로를 수동 실행한다. 입력 `server`에서 배포할 서버를 고른다.
2. 러너가 `docker/Dockerfile`의 `build` 스테이지를 실행한다. 이 스테이지는 화면 검사와 `gradle build`를 포함한다.
3. 러너가 `runtime` 이미지를 커밋 SHA 태그와 `latest` 태그로 빌드한다.
4. 러너가 `docker compose -f deploy/compose.yml up -d --wait`를 실행한다.

레지스트리는 사용하지 않는다. 롤백은 `IMAGE_TAG=<이전 커밋 SHA> docker compose -f deploy/compose.yml up -d --wait`로 한다.
```

- [ ] **Step 5: html 뷰에 같은 변경 반영**

html의 `<section id="s2">`, `<section id="s7">`, 머리말 `.meta`에서 Step 1~4와 같은 문장으로 바꾼다. 표는 기존 `<div class="tbl"><table>` 형식을 따른다.

확인:

```bash
grep -n -E "docker save|host.json:/data/host.json|사용자 검토 대기|~/homeview" docs/superpowers/specs/2026-10-22-homeview-design.*
```

Expected: 출력 없음.

- [ ] **Step 6: 커밋 (git-master)**

메시지: `docs: 배포 절차를 러너 CD로 바꾸고 호스트 파일을 디렉터리로 마운트 (SPEC-HV-001)`

---

### Task 2: Gradle 골격과 Spring Boot 앱

**Files:**
- Create: `.gitignore`, `.editorconfig`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `settings.gradle.kts`, `build.gradle.kts`
- Create: `src/main/kotlin/com/bombo/homeview/HomeviewApplication.kt`, `src/main/kotlin/com/bombo/homeview/ClockConfig.kt`
- Create: `src/main/resources/application.yaml`, `src/test/resources/junit-platform.properties`

**Interfaces:**
- Produces: `./gradlew build`가 `build/libs/app.jar`를 만든다. `Clock` 빈(zone `Asia/Seoul`). actuator `GET /actuator/health/readiness`.

- [ ] **Step 1: Gradle 래퍼 복사**

STOCK_GUIDE의 래퍼를 그대로 복사한다.

```bash
SG=/Users/bombo/Documents/900_PROJECTS/001_STOCK_GUIDE
cp "$SG/gradlew" "$SG/gradlew.bat" .
mkdir -p gradle && cp -R "$SG/gradle/wrapper" gradle/
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip`

- [ ] **Step 2: `.editorconfig` 복사, `.gitignore` 작성**

```bash
cp ~/Documents/0000_CLAUDE_WORKSPACE/templates/kotlin/.editorconfig .
```

`.gitignore`:

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.kotlin/
.idea/
*.iml
out/
.DS_Store
node_modules/
web/dist/
src/main/resources/static/
```

`src/main/resources/static/`은 Docker 빌드가 화면 결과를 복사하는 위치이다. 저장소에 커밋하지 않는다.

- [ ] **Step 3: `settings.gradle.kts`, `build.gradle.kts` 작성**

`settings.gradle.kts`:

```kotlin
rootProject.name = "homeview"
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jmailen.kotlinter") version "5.7.0"
}

group = "com.bombo.homeview"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
```

- [ ] **Step 4: 앱 진입점과 `Clock` 빈 작성**

`src/main/kotlin/com/bombo/homeview/HomeviewApplication.kt`:

```kotlin
package com.bombo.homeview

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class HomeviewApplication

fun main(args: Array<String>) {
    runApplication<HomeviewApplication>(*args)
}
```

`src/main/kotlin/com/bombo/homeview/ClockConfig.kt`:

```kotlin
package com.bombo.homeview

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
```

- [ ] **Step 5: 설정 파일 작성**

`src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: homeview

server:
  port: 8080

management:
  endpoint:
    health:
      probes:
        enabled: true
```

`src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.testinstance.lifecycle.default=per_class
```

- [ ] **Step 6: 빌드 확인**

```bash
./gradlew formatKotlin && ./gradlew lintKotlin && ./gradlew build
ls build/libs/app.jar
```

Expected: `BUILD SUCCESSFUL`. `build/libs/app.jar`가 있다.

- [ ] **Step 7: 기동 확인**

```bash
java -jar build/libs/app.jar & APP_PID=$!
sleep 8; curl -s localhost:8080/actuator/health/readiness; kill $APP_PID
```

Expected: `{"status":"UP"}`

- [ ] **Step 8: 커밋 (git-master)**

메시지: `build: Gradle 골격과 Spring Boot 앱 추가 (SPEC-HV-001)`

---

### Task 3: `/api/topology` 응답 모델과 빈 응답

이 작업은 SPEC 4절의 응답 모델을 만든다. 출처는 아직 연결하지 않는다. 그래서 세 출처 상태는 `ERROR`이고, 노드와 엣지는 비어 있다. 계획 2가 컨트롤러 내부를 실제 조립 결과로 바꾼다.

**Files:**
- Create: `src/main/kotlin/com/bombo/homeview/topology/Topology.kt`
- Create: `src/main/kotlin/com/bombo/homeview/topology/TopologyController.kt`
- Test: `src/test/kotlin/com/bombo/homeview/topology/TopologyControllerTest.kt`

**Interfaces:**
- Consumes: `Clock` 빈 (Task 2).
- Produces (계획 2·3이 사용):
  - `enum class SourceStatus { OK, STALE, ERROR }`
  - `data class SourceState(val status: SourceStatus, val snapshotAt: OffsetDateTime? = null, val message: String? = null)`
  - `data class Sources(val docker: SourceState, val prometheus: SourceState, val host: SourceState)`
  - `enum class NodeKind { HOST, COMPOSE_PROJECT, CONTAINER, NETWORK, VOLUME, HOST_SERVICE, LISTEN_PORT }`
  - `enum class EdgeKind { NETWORK_ATTACH, MOUNT, PORT_MAPPING, PORT_OWNER }`
  - `data class NodeMetrics(val cpuPercent: Double?, val memBytes: Long?, val memLimitBytes: Long?)`
  - `data class Node(val id: String, val kind: NodeKind, val parent: String?, val label: String, val status: String?, val attrs: Map<String, Any?>, val metrics: NodeMetrics?)`
  - `data class Edge(val id: String, val kind: EdgeKind, val source: String, val target: String, val attrs: Map<String, Any?>)`
  - `data class Topology(val generatedAt: OffsetDateTime, val sources: Sources, val nodes: List<Node>, val edges: List<Edge>)`
  - `GET /api/topology` → `Topology` JSON

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/kotlin/com/bombo/homeview/topology/TopologyControllerTest.kt`:

```kotlin
package com.bombo.homeview.topology

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@WebMvcTest(TopologyController::class)
@Import(TopologyControllerTest.FixedClockConfig::class)
@DisplayName("TopologyController")
class TopologyControllerTest(
    @Autowired private val mvc: MockMvcTester,
) {
    @TestConfiguration
    class FixedClockConfig {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-22T01:00:05Z"), ZoneId.of("Asia/Seoul"))
    }

    @Nested
    @DisplayName("GET /api/topology")
    inner class GetTopology {
        @Test
        @DisplayName("생성 시각을 서울 시간대 오프셋으로 반환한다")
        fun returnsGeneratedAtWithSeoulOffset() {
            assertThat(mvc.get().uri("/api/topology"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.generatedAt")
                .isEqualTo("2026-10-22T10:00:05+09:00")
        }

        @Test
        @DisplayName("출처가 연결되지 않았으므로 세 출처 상태가 모두 ERROR이다")
        fun returnsErrorForAllSources() {
            val body = assertThat(mvc.get().uri("/api/topology")).hasStatusOk().bodyJson()
            body.extractingPath("$.sources.docker.status").isEqualTo("ERROR")
            body.extractingPath("$.sources.prometheus.status").isEqualTo("ERROR")
            body.extractingPath("$.sources.host.status").isEqualTo("ERROR")
        }

        @Test
        @DisplayName("노드와 엣지가 비어 있다")
        fun returnsEmptyNodesAndEdges() {
            val body = assertThat(mvc.get().uri("/api/topology")).hasStatusOk().bodyJson()
            body.extractingPath("$.nodes").asArray().isEmpty()
            body.extractingPath("$.edges").asArray().isEmpty()
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.bombo.homeview.topology.TopologyControllerTest"
```

Expected: FAIL, `Unresolved reference 'TopologyController'` 컴파일 오류.

- [ ] **Step 3: 응답 모델 작성**

`src/main/kotlin/com/bombo/homeview/topology/Topology.kt`:

```kotlin
package com.bombo.homeview.topology

import java.time.OffsetDateTime

enum class SourceStatus { OK, STALE, ERROR }

data class SourceState(
    val status: SourceStatus,
    val snapshotAt: OffsetDateTime? = null,
    val message: String? = null,
)

data class Sources(
    val docker: SourceState,
    val prometheus: SourceState,
    val host: SourceState,
)

enum class NodeKind { HOST, COMPOSE_PROJECT, CONTAINER, NETWORK, VOLUME, HOST_SERVICE, LISTEN_PORT }

enum class EdgeKind { NETWORK_ATTACH, MOUNT, PORT_MAPPING, PORT_OWNER }

data class NodeMetrics(
    val cpuPercent: Double?,
    val memBytes: Long?,
    val memLimitBytes: Long?,
)

data class Node(
    val id: String,
    val kind: NodeKind,
    val parent: String?,
    val label: String,
    val status: String?,
    val attrs: Map<String, Any?>,
    val metrics: NodeMetrics?,
)

data class Edge(
    val id: String,
    val kind: EdgeKind,
    val source: String,
    val target: String,
    val attrs: Map<String, Any?>,
)

data class Topology(
    val generatedAt: OffsetDateTime,
    val sources: Sources,
    val nodes: List<Node>,
    val edges: List<Edge>,
)
```

- [ ] **Step 4: 컨트롤러 작성**

`src/main/kotlin/com/bombo/homeview/topology/TopologyController.kt`:

```kotlin
package com.bombo.homeview.topology

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime

@RestController
class TopologyController(
    private val clock: Clock,
) {
    @GetMapping("/api/topology")
    fun topology(): Topology {
        val notConnected = SourceState(SourceStatus.ERROR, message = NOT_CONNECTED)
        return Topology(
            generatedAt = OffsetDateTime.now(clock),
            sources = Sources(docker = notConnected, prometheus = notConnected, host = notConnected),
            nodes = emptyList(),
            edges = emptyList(),
        )
    }

    private companion object {
        const val NOT_CONNECTED = "아직 연결하지 않았습니다"
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.bombo.homeview.topology.TopologyControllerTest"
```

Expected: PASS, 테스트 3개.

`returnsGeneratedAtWithSeoulOffset`만 `2026-10-22T01:00:05Z`로 실패하면, Jackson이 날짜를 UTC로 바꾼 것이다. `application.yaml`에 아래를 추가하고 다시 실행한다.

```yaml
spring:
  jackson:
    datatype:
      datetime:
        adjust-dates-to-context-time-zone: false
```

- [ ] **Step 6: 린트와 전체 빌드**

```bash
./gradlew formatKotlin && ./gradlew lintKotlin && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋 (git-master)**

메시지: `feat: 토폴로지 응답 모델과 빈 응답 API 추가 (SPEC-HV-001)`

---

### Task 4: 화면 골격

화면은 `/api/topology`를 한 번 요청하고 세 출처 상태를 표시한다. 그래프는 계획 3에서 만든다.

**Files:**
- Create: `web/package.json`, `web/package-lock.json`, `web/tsconfig.json`, `web/vite.config.ts`, `web/eslint.config.js`
- Create: `web/index.html`, `web/src/main.tsx`, `web/src/App.tsx`
- Test: `web/src/App.test.tsx`

**Interfaces:**
- Consumes: `GET /api/topology` 응답의 `sources` (Task 3).
- Produces: `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build`(결과 `web/dist/`). `export interface TopologyResponse`, `export function App()`.

- [ ] **Step 1: 설정 파일 작성**

`web/package.json`:

```json
{
  "name": "homeview-web",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "typecheck": "tsc --noEmit",
    "lint": "eslint .",
    "test": "vitest run"
  },
  "dependencies": {
    "react": "19.3.1",
    "react-dom": "19.3.1"
  },
  "devDependencies": {
    "@eslint/js": "10.0.1",
    "@testing-library/dom": "10.4.2",
    "@testing-library/react": "16.3.3",
    "@types/react": "19.3.0",
    "@types/react-dom": "19.3.0",
    "@vitejs/plugin-react": "6.1.2",
    "eslint": "10.13.0",
    "eslint-plugin-react-hooks": "7.1.1",
    "globals": "17.13.0",
    "jsdom": "30.1.3",
    "typescript": "6.0.3",
    "typescript-eslint": "8.72.1",
    "vite": "8.3.5",
    "vitest": "5.0.5"
  }
}
```

`web/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true
  },
  "include": ["src", "vite.config.ts"]
}
```

`web/vite.config.ts`:

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  build: { outDir: 'dist' },
  test: { environment: 'jsdom' },
})
```

`web/eslint.config.js`:

```js
import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
)
```

- [ ] **Step 2: 의존성 설치**

```bash
cd web && npm install
```

Expected: `package-lock.json`이 생기고 peer 경고 중 `typescript` 관련 경고가 없다.

- [ ] **Step 3: 실패하는 테스트 작성**

`web/src/App.test.tsx`:

```tsx
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App, type TopologyResponse } from './App'

const sample: TopologyResponse = {
  generatedAt: '2026-10-22T10:00:05+09:00',
  sources: {
    docker: { status: 'OK' },
    prometheus: { status: 'ERROR', message: '아직 연결하지 않았습니다' },
    host: { status: 'STALE' },
  },
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('App', () => {
  it('출처 세 개의 상태를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(sample), { status: 200 })))

    render(<App />)

    expect(await screen.findByText('docker: OK')).toBeTruthy()
    expect(screen.getByText('prometheus: ERROR')).toBeTruthy()
    expect(screen.getByText('host: STALE')).toBeTruthy()
  })

  it('API 요청이 실패하면 연결 끊김을 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    render(<App />)

    expect(await screen.findByText('연결 끊김')).toBeTruthy()
  })
})
```

- [ ] **Step 4: 테스트 실패 확인**

```bash
cd web && npm run test
```

Expected: FAIL, `Failed to resolve import "./App"`.

- [ ] **Step 5: 화면 작성**

`web/src/App.tsx`:

```tsx
import { useEffect, useState } from 'react'

type SourceStatus = 'OK' | 'STALE' | 'ERROR'

interface SourceState {
  status: SourceStatus
  snapshotAt?: string | null
  message?: string | null
}

export interface TopologyResponse {
  generatedAt: string
  sources: { docker: SourceState; prometheus: SourceState; host: SourceState }
}

const SOURCE_NAMES = ['docker', 'prometheus', 'host'] as const

export function App() {
  const [topology, setTopology] = useState<TopologyResponse | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetch('/api/topology')
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        return response.json() as Promise<TopologyResponse>
      })
      .then(setTopology)
      .catch(() => setFailed(true))
  }, [])

  return (
    <main>
      <h1>homeview</h1>
      {failed && <p>연결 끊김</p>}
      {topology && (
        <ul>
          {SOURCE_NAMES.map((name) => (
            <li key={name}>{`${name}: ${topology.sources[name].status}`}</li>
          ))}
        </ul>
      )}
    </main>
  )
}
```

`web/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

`web/index.html`:

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>homeview</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 6: 품질 게이트 확인**

```bash
cd web && npm run typecheck && npm run lint && npm run test && npm run build
ls dist/index.html
```

Expected: 테스트 2개 통과, 린트 오류 0개, `dist/index.html`이 있다.

- [ ] **Step 7: 커밋 (git-master)**

메시지: `feat: 출처 상태를 표시하는 화면 골격 추가 (SPEC-HV-001)`

---

### Task 5: Docker 이미지

**Files:**
- Create: `docker/Dockerfile`, `.dockerignore`

**Interfaces:**
- Consumes: `web/` 스크립트 (Task 4), `./gradlew build` (Task 2).
- Produces: 스테이지 `build`(CI 대상), `runtime`(실행 이미지, `/app/app.jar`, 사용자 `app`, `wget` 포함).

- [ ] **Step 1: Dockerfile 작성**

`docker/Dockerfile`:

```dockerfile
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
```

`.dockerignore`:

```
.git
.gradle
**/build
.idea
*.iml
.kotlin
docs
deploy
.github
collector
web/node_modules
web/dist
src/main/resources/static
```

- [ ] **Step 2: 홈서버에서 빌드 확인**

로컬 Docker 데몬은 꺼져 있다. 작업 트리를 홈서버 임시 디렉터리로 보내서 빌드한다.

```bash
TMP=/tmp/hv-build-$(date +%s)
ssh homeserver "mkdir -p $TMP"
tar --exclude=.git --exclude=web/node_modules --exclude=build --exclude=.gradle -czf - . | ssh homeserver "tar -xzf - -C $TMP"
ssh homeserver "cd $TMP && docker build --progress=plain -f docker/Dockerfile --target build . && docker build -q -f docker/Dockerfile -t homeview-app:dev ."
ssh homeserver "docker run --rm --entrypoint sh homeview-app:dev -c 'id && which wget && ls -l /app/app.jar'"
ssh homeserver "rm -rf $TMP && docker rmi homeview-app:dev"
```

Expected: 두 빌드가 성공한다. `uid=...(app)`, `/usr/bin/wget`, `/app/app.jar`가 출력된다.

- [ ] **Step 3: 커밋 (git-master)**

메시지: `build: 화면과 백엔드를 함께 빌드하는 Docker 이미지 추가 (SPEC-HV-001)`

---

### Task 6: 배포 compose

**Files:**
- Create: `deploy/compose.yml`

**Interfaces:**
- Consumes: 이미지 `homeview-app:${IMAGE_TAG}` (Task 5).
- Produces: compose 프로젝트 `homeview`. 컨테이너 `homeview-app`, `homeview-proxy`. 네트워크 `homeview_internal`. 앱 안에서 프록시 주소 `http://homeview-proxy:2375`, Prometheus 주소 `http://prometheus:9090`(계획 2가 사용).

- [ ] **Step 1: compose 작성**

`deploy/compose.yml`:

```yaml
# homeview 배포 정의. CD(.github/workflows/cd.yml)가 홈서버 러너에서 실행한다.
# 수동 롤백: IMAGE_TAG=<이전 커밋 SHA> docker compose -f deploy/compose.yml up -d --wait
name: homeview

services:
  proxy:
    image: tecnativa/docker-socket-proxy:v0.5.0
    container_name: homeview-proxy
    restart: unless-stopped
    environment:
      CONTAINERS: 1
      NETWORKS: 1
      VOLUMES: 1
      POST: 0
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks: [internal]
    mem_limit: 64m

  app:
    image: homeview-app:${IMAGE_TAG:-latest}
    container_name: homeview-app
    restart: unless-stopped
    depends_on: [proxy]
    ports:
      - "100.94.174.67:3001:8080"
    volumes:
      - /var/lib/homeview:/data:ro
    networks: [internal, monitoring]
    mem_limit: 512m
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 60s

networks:
  internal:
    name: homeview_internal
  monitoring:
    name: monitoring_monitoring
    external: true
```

- [ ] **Step 2: 문법 확인 (홈서버)**

```bash
ssh homeserver "docker compose -f - config --quiet" < deploy/compose.yml && echo OK
```

Expected: `OK`

- [ ] **Step 3: 커밋 (git-master)**

메시지: `build: 앱과 Docker 소켓 프록시 배포 compose 추가 (SPEC-HV-001)`

---

### Task 7: CI/CD 워크플로와 첫 PR

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/cd.yml`

**Interfaces:**
- Consumes: Task 0의 조직 러너, Task 5의 `build`·`runtime` 스테이지, Task 6의 compose.
- Produces: PR 체크 이름 `build`(Task 8 룰셋이 사용). 워크플로 `cd`(입력 `server`).

- [ ] **Step 1: CI 워크플로 작성**

`.github/workflows/ci.yml`:

```yaml
name: ci

on:
  pull_request:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: homeserver
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4

      - name: 화면 검사와 gradle build
        run: docker build --progress=plain -f docker/Dockerfile --target build .
```

- [ ] **Step 2: CD 워크플로 작성**

`.github/workflows/cd.yml`:

```yaml
name: cd

on:
  workflow_dispatch:
    inputs:
      server:
        description: 배포할 서버 (러너 라벨)
        type: choice
        required: true
        options:
          - homeserver

permissions:
  contents: read

concurrency:
  group: deploy-homeview-${{ inputs.server }}
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: ${{ inputs.server }}
    timeout-minutes: 20
    env:
      IMAGE_TAG: ${{ github.sha }}
    steps:
      - uses: actions/checkout@v4

      # 수동 실행은 main이 아닌 브랜치에서도 가능하다. 배포 전에 다시 검증한다.
      - name: 화면 검사와 gradle build
        run: docker build --progress=plain -f docker/Dockerfile --target build .

      - name: 이미지 빌드
        run: |
          docker build --progress=plain -f docker/Dockerfile --target runtime \
            -t "homeview-app:$IMAGE_TAG" -t "homeview-app:latest" .

      - name: 배포
        run: docker compose -f deploy/compose.yml up -d --wait --wait-timeout 180

      - name: 실패 시 상태 출력
        if: failure()
        run: |
          docker compose -f deploy/compose.yml ps
          docker compose -f deploy/compose.yml logs --tail 100
```

`concurrency` 그룹 이름에 `homeview`를 넣는다. 조직 러너를 STOCK_GUIDE와 공유하므로, 저장소가 달라도 그룹 이름이 겹치지 않게 한다.

- [ ] **Step 3: 커밋 (git-master)**

메시지: `ci: 홈서버 러너 CI와 수동 CD 워크플로 추가 (SPEC-HV-001)`

- [ ] **Step 4: push와 PR 생성 (git-master)**

git-master에 `feat/hv-skeleton-ci-cd` 브랜치 push와 main 대상 PR 생성을 지시한다. PR 본문 끝 줄은 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`이다.

- [ ] **Step 5: CI 결과 확인**

```bash
gh pr checks --watch -R "$ORG/homeview"
```

Expected: `build` 체크가 pass. 실패하면 로그(`gh run view --log-failed -R "$ORG/homeview"`)를 보고 원인 작업으로 돌아간다.

- [ ] **Step 6: PR merge (사용자 확인 후)**

CI 통과를 사용자에게 알리고 merge 확인을 받는다. 확인을 받으면 git-master에 `gh pr merge --merge --delete-branch`를 지시한다.

---

### Task 8: public 전환, main 보호, 첫 배포

**Files:** 없음

**Interfaces:**
- Consumes: Task 7의 `build` 체크, `cd` 워크플로.

- [ ] **Step 1: 이력의 비밀값 점검**

```bash
git log -p --all | grep -n -i -E "password|passwd|secret|token|AKIA|BEGIN .*PRIVATE KEY" | grep -v -E "fork-pr|registration-token|remove-token|REG_TOKEN|REMOVE_TOKEN" || echo "없음"
```

Expected: `없음`. 다른 결과가 나오면 멈추고 사용자에게 보여 준다.

- [ ] **Step 2: (메인 세션, 사용자 승인 후) public 전환**

```bash
gh repo edit "$ORG/homeview" --visibility public --accept-visibility-change-consequences
gh repo view "$ORG/homeview" --json visibility
```

Expected: `{"visibility":"PUBLIC"}`

- [ ] **Step 3: (메인 세션, 사용자 승인 후) main 보호 룰셋 생성**

STOCK_GUIDE의 룰셋 `main 보호`와 같은 규칙을 쓴다. 먼저 원본을 확인한다.

```bash
SG_RULESET_ID=$(gh api "repos/$ORG/STOCK_GUIDE/rulesets" --jq '.[] | select(.name=="main 보호") | .id')
gh api "repos/$ORG/STOCK_GUIDE/rulesets/$SG_RULESET_ID" --jq '{name, target, enforcement, conditions, rules}' > /tmp/hv-ruleset.json
cat /tmp/hv-ruleset.json
gh api -X POST "repos/$ORG/homeview/rulesets" --input /tmp/hv-ruleset.json
gh api "repos/$ORG/homeview/rulesets" --jq '.[] | {name, enforcement}'
```

Expected: 원본 규칙에 `pull_request`와 `required_status_checks`(context `build`)가 있다. homeview에 `{"name":"main 보호","enforcement":"active"}`가 생긴다.

- [ ] **Step 4: CD 실행**

```bash
gh workflow run cd -R "$ORG/homeview" --ref main -f server=homeserver
sleep 5; gh run watch -R "$ORG/homeview" $(gh run list -R "$ORG/homeview" -w cd -L 1 --json databaseId --jq '.[0].databaseId')
```

Expected: 실행이 `success`로 끝난다.

- [ ] **Step 5: 배포 결과 확인 (맥, tailnet)**

```bash
curl -s http://100.94.174.67:3001/api/topology
curl -s http://100.94.174.67:3001/ | grep -o "<title>homeview</title>"
ssh homeserver "docker compose -p homeview ps --format '{{.Name}} {{.Status}}'"
```

Expected:
- 첫 줄: `generatedAt`과 `"status":"ERROR"` 세 개, `"nodes":[]`, `"edges":[]`가 있는 JSON.
- 둘째 줄: `<title>homeview</title>`
- 셋째 줄: `homeview-app Up ... (healthy)`, `homeview-proxy Up ...`

- [ ] **Step 6: 보기 전용 경계 확인 (AC-11 일부)**

```bash
ssh homeserver "docker run --rm --network homeview_internal curlimages/curl:8.23.0 -s -o /dev/null -w '%{http_code}\n' -X POST http://homeview-proxy:2375/containers/homeview-app/restart"
ssh homeserver "docker run --rm --network homeview_internal curlimages/curl:8.23.0 -s -o /dev/null -w '%{http_code}\n' http://homeview-proxy:2375/containers/json"
```

Expected: 첫 줄 `403`, 둘째 줄 `200`.

- [ ] **Step 7: 사용자에게 결과 보고**

보고 항목: 저장소 URL, PR URL, CD 실행 URL, 위 Step 5·6 출력, 다음 계획(계획 2) 시작 여부.

---

## Self-Review 기록

- SPEC 2.1 구성(프록시, 앱, 네트워크, 포트): Task 6. 7.1 설정 값: Task 1, Task 6. 7.2 배포 절차: Task 1(개정), Task 7·8(구현). 4절 응답 모델: Task 3. 8.3 품질 게이트: Task 2·3(gradle), Task 4(npm), Task 5(Docker 안에서 둘 다).
- 이 계획에 없는 SPEC 항목은 계획 2(3절 Source·Assembler, 6절 갱신·오류, 7.3 수집기)와 계획 3(5절 화면)에 있다.
- AC-11은 Task 8 Step 6에서 프록시 쪽만 확인한다. 앱 코드에 GET만 있다는 확인은 계획 2에서 한다. AC-12(Tailscale IP에만 포트)는 Task 6의 포트 바인딩으로 충족하고 Task 8 Step 5로 확인한다.
