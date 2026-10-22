# Homeview 설계 (SPEC-HV-001)

- 작성일: 2026-10-22
- 상태: 사용자 검토 대기
- 레이아웃 시안: https://claude.ai/artifact/Ve7YcaVgX3SdXzPt6w4CJx (시안 A 채택)

## 1. 목적과 범위

홈서버(bombo-server, Tailscale `<TAILNET_IP>`)의 상태와, 서버에 연결된 인프라를 인터랙티브 그래프로 보여주는 **보기 전용** 웹 화면을 만든다.

**범위에 포함**
- Docker 토폴로지: compose 프로젝트 → 컨테이너 → 네트워크 · 볼륨 · 포트 관계와 상태(running/exited, health)
- 리소스 메트릭 오버레이: 기존 Prometheus(cAdvisor, node-exporter)에서 컨테이너별 · 호스트 CPU, 메모리, 디스크의 현재 값
- 호스트 레벨 서비스: 실행 중인 systemd 서비스, 리스닝 포트와 노출 범위

**범위에서 제외**
- 컨테이너 시작 · 중지 · 재시작, 로그 보기, compose 조작 등 모든 제어 기능
- 앱 자체 인증 (Tailscale IP에만 바인딩하는 것으로 대신함)
- 메트릭 과거 추이 그래프 (Grafana 링크로 대신함)
- Tailscale 피어 목록
- 노출 범위 레인 보기(시안 C): 나중에 추가할 후보

## 2. 전체 구조

```
[홈서버 호스트]
 ├─ homeview-collector (systemd 타이머, 30초 간격)
 │    ss -tlnp, systemctl 결과 → /var/lib/homeview/host.json
 │
 └─ Docker: compose 프로젝트 "homeview"
      ├─ docker-socket-proxy ── /var/run/docker.sock (ro)
      │    CONTAINERS=1, NETWORKS=1, VOLUMES=1, POST=0, 나머지 0
      │    homeview_internal 네트워크에만 연결, 호스트 포트 없음
      │
      └─ homeview-app (Spring Boot + React 정적 파일)
           ├─ DockerSource     → http://docker-socket-proxy:2375
           ├─ MetricsSource    → http://prometheus:9090 (monitoring_monitoring 외부 네트워크)
           ├─ HostSource       → /data/host.json (ro 마운트)
           ├─ TopologyAssembler
           └─ GET /api/topology + React 화면
           포트: <TAILNET_IP>:3001:8080
```

**결정 사항**
- 기존 `~/monitoring` compose는 수정하지 않는다. `monitoring_monitoring` 네트워크에 `external: true`로 참여만 한다.
- 보기 전용 경계는 두 겹으로 둔다. 하나는 프록시 설정(`POST=0`)이고, 다른 하나는 앱 코드에 GET 요청만 두는 것이다.
- collector는 `pid: host` 같은 넓은 권한 대신 호스트의 systemd 타이머로 돌린다. 출력은 임시 파일에 쓴 뒤 `mv`로 교체해 원자적으로 바꾼다.
- 코드는 맥의 `/Users/bombo/Documents/900_PROJECT/999_homeview` 저장소에서 관리하고, 서버에는 이미지와 compose 파일, collector만 배포한다.

## 3. 구성 요소

| 단위 | 책임 | 입력 | 출력 |
|---|---|---|---|
| `collector/homeview-collect.sh` | 호스트 정보 수집 | `ss -tlnpH`, `systemctl list-units --type=service --state=running` | `/var/lib/homeview/host.json` |
| `collector/homeview-collector.{service,timer}` | 30초마다 수집 실행 | — | — |
| `DockerSource` | Docker 조회 | 프록시 `GET /containers/json?all=1`, `/networks`, `/volumes`, `/containers/{id}/json` | `DockerSnapshot` 또는 실패 |
| `MetricsSource` | 현재 메트릭 조회 | PromQL instant query | `MetricsSnapshot` 또는 실패 |
| `HostSource` | 호스트 스냅샷 읽기 | `host.json` | `HostSnapshot` 또는 STALE/실패 |
| `TopologyAssembler` | 세 출처를 하나의 그래프로 합침 (순수 함수) | 세 스냅샷과 각 상태 | `Topology` |
| `TopologyController` | `GET /api/topology`, 5초 캐시 | — | JSON |
| 프론트엔드 | 그래프 · 상세 패널 · 위쪽 바 | `/api/topology` | 화면 |

### host.json 형식

```json
{
  "snapshotAt": "2026-10-22T10:00:00+09:00",
  "hostname": "bombo-server",
  "listeners": [
    { "proto": "tcp", "address": "0.0.0.0", "port": 22, "process": "sshd", "pid": 1234, "unit": "ssh.service" }
  ],
  "services": [
    { "unit": "tailscaled.service", "active": "active", "sub": "running" }
  ]
}
```

### PromQL

- 컨테이너 CPU %: `sum by (name) (rate(container_cpu_usage_seconds_total{name!=""}[1m])) * 100`
- 컨테이너 메모리: `container_memory_working_set_bytes{name!=""}`, 제한: `container_spec_memory_limit_bytes{name!=""}`
- 호스트 CPU %: `100 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100`
- 호스트 메모리: `node_memory_MemTotal_bytes`, `node_memory_MemAvailable_bytes`
- 호스트 디스크 `/`: `node_filesystem_size_bytes{mountpoint="/"}`, `node_filesystem_avail_bytes{mountpoint="/"}`
- 가동 시간: `time() - node_boot_time_seconds`

## 4. 데이터 모델

### 노드

| kind | id 형식 | 출처 | 주요 속성 |
|---|---|---|---|
| `HOST` | `host:<hostname>` | host.json + node-exporter | 가동 시간, CPU · 메모리 · 디스크 |
| `COMPOSE_PROJECT` | `compose:<project>` | 라벨 `com.docker.compose.project` | compose 파일 경로, 실행 수 / 전체 수 |
| `CONTAINER` | `container:<name>` | Docker + cAdvisor | 이미지, 상태, health, 가동 시간, CPU %, 메모리 / 제한. `parent`는 compose 노드 (없으면 null) |
| `NETWORK` | `network:<name>` | Docker | 드라이버, 서브넷 |
| `VOLUME` | `volume:<name>` 또는 `bind:<host path>` | Docker | named / bind |
| `HOST_SERVICE` | `service:<unit>` | host.json | active, sub 상태 |
| `LISTEN_PORT` | `port:<proto>:<address>:<port>` | host.json | 소유 프로세스, 노출 범위 |

기본 네트워크인 `host`, `none`과, 연결된 컨테이너가 없는 네트워크는 표시하지 않는다.

### 엣지

| kind | source → target | 속성 |
|---|---|---|
| `NETWORK_ATTACH` | CONTAINER → NETWORK | IP, alias |
| `MOUNT` | CONTAINER → VOLUME | 컨테이너 경로, ro 여부 |
| `PORT_MAPPING` | LISTEN_PORT → CONTAINER | 컨테이너 포트, 노출 범위 |
| `PORT_OWNER` | LISTEN_PORT → HOST_SERVICE | — |

### 노출 범위 분류

| 값 | 판정 기준 |
|---|---|
| `PUBLIC` | `0.0.0.0`, `::`, `*` |
| `TAILNET` | `100.64.0.0/10` 또는 `fd7a:115c:a1e0::/48` |
| `LOCALHOST` | `127.0.0.0/8`, `::1`, `127.0.0.53%lo` 같은 zone 표기 포함 |
| `INTERNAL` | 호스트 포트 없이 컨테이너가 노출만 한 포트 |
| `OTHER` | 위에 해당하지 않는 주소 (예: LAN IP) |

### 포트 매핑 규칙

- `ss`의 소유 프로세스가 `docker-proxy`인 리스너는 Docker 포트 매핑의 `(HostIp, PublicPort)`와 맞춰 해당 컨테이너에 `PORT_MAPPING` 엣지로 잇는다.
- `unit`이 있는 리스너는 해당 `HOST_SERVICE`에 `PORT_OWNER` 엣지로 잇는다.
- 소유 프로세스를 알 수 없는 리스너는 `owner: "UNKNOWN"`으로 두고 화면에 "미확인"으로 표시한다.

### 응답 예시

```json
{
  "generatedAt": "2026-10-22T10:00:05+09:00",
  "sources": {
    "docker":     { "status": "OK" },
    "prometheus": { "status": "OK" },
    "host":       { "status": "STALE", "snapshotAt": "2026-10-22T09:58:00+09:00", "message": "호스트 정보가 2분 전 것입니다" }
  },
  "nodes": [
    { "id": "container:grafana", "kind": "CONTAINER", "parent": "compose:monitoring", "label": "grafana",
      "status": "RUNNING",
      "attrs": { "image": "grafana/grafana:latest", "health": null, "startedAt": "2026-08-15T..." },
      "metrics": { "cpuPercent": 0.4, "memBytes": 120000000, "memLimitBytes": 1073741824 } }
  ],
  "edges": [
    { "id": "PORT_MAPPING:port:tcp:<TAILNET_IP>:3000->container:grafana", "kind": "PORT_MAPPING",
      "source": "port:tcp:<TAILNET_IP>:3000", "target": "container:grafana",
      "attrs": { "containerPort": 3000, "exposure": "TAILNET" } }
  ]
}
```

`sources.*.status` 값은 `OK`, `STALE`, `ERROR` 중 하나다.

## 5. 화면 (시안 A: 그래프 중심)

- **위쪽 바**: 호스트명, CPU · 메모리 · 디스크, 가동 시간, 출처 상태 배지 세 개, "마지막 갱신 n초 전", 새로고침 버튼, 필터(노출 범위, 볼륨 표시 여부, 호스트 서비스 표시 여부)
- **캔버스**: React Flow 사용. compose 프로젝트는 그룹 노드로 컨테이너를 감싼다. 포트는 노출 범위 색으로 칠한다(PUBLIC 빨강, TAILNET 파랑, LOCALHOST 보라, INTERNAL 회색). 줌과 드래그를 지원한다.
- **배치**: elkjs layered. 왼쪽에 포트, 가운데에 compose 그룹과 컨테이너, 오른쪽에 네트워크와 볼륨을 둔다. 노드가 새로 생기거나 사라질 때만 다시 배치한다. 드래그한 위치는 localStorage에 저장한다.
- **상세 패널(오른쪽)**: 선택한 노드의 속성과 연결된 엣지를 보여준다. 컨테이너와 호스트 노드에는 Grafana 링크(`http://<TAILNET_IP>:3000`)를 둔다.
- **폰 화면(폭 640px 이하)**: 상세 패널이 아래에서 올라오는 시트로 바뀐다.

## 6. 갱신과 오류 처리

- 프론트는 15초마다 다시 불러온다. 탭이 숨겨지면 멈추고, 다시 보이면 즉시 한 번 불러온다.
- 백엔드는 결과를 5초 동안 캐시한다. 출처별 HTTP 타임아웃은 3초이고, 세 출처를 병렬로 조회한다.
- host.json의 `snapshotAt`이 90초보다 오래되면 STALE로 표시하고, 마지막 스냅샷 데이터를 그대로 쓴다.

| 상황 | 백엔드 | 화면 |
|---|---|---|
| Docker 프록시 실패 | `docker: ERROR`, Docker 출처 노드 없음 | 빨간 배너, 호스트 노드는 표시 |
| Prometheus 실패 | `prometheus: ERROR`, metrics null | 수치를 "—"로 표시 |
| host.json 오래됨 | `host: STALE` | 노란 배지에 "n분 전" 표시 |
| host.json 없음 · 깨짐 | `host: ERROR`, 호스트 서비스 · 포트 노드 없음 | 빨간 배지 |
| API 실패 | — | 마지막 그래프를 유지하고 "연결 끊김 · n초 전 데이터" 표시 |

## 7. 배포

- compose 프로젝트 이름은 `homeview`이고, 서버 경로는 `~/homeview`로 한다.
- 네트워크: `homeview_internal`(프록시와 앱), `monitoring_monitoring`(external, 앱만)
- 포트: `<TAILNET_IP>:3001:8080`
- 볼륨: `/var/lib/homeview/host.json:/data/host.json:ro`
- 메모리 제한: 앱 `512m`, 프록시 `64m`
- 이미지는 맥에서 `linux/amd64`로 빌드한 뒤 `docker save | ssh homeserver docker load`로 옮긴다. 레지스트리는 두지 않는다.
- collector는 `/usr/local/bin/homeview-collect.sh`와 `/etc/systemd/system/homeview-collector.{service,timer}`에 설치하고 root로 실행한다. 다른 사용자의 소켓 소유 프로세스를 보려면 `ss -p`에 root 권한이 필요하기 때문이다. 출력 파일은 `0644`로 둔다.

## 8. 테스트

테스트 코드는 `kotlin-junit5-test.md` 규칙을 따른다. 식별자는 ASCII로 짓고, 한글은 `@DisplayName`에만 쓰며, `@Nested inner class`로 분기를 나눈다.

| 대상 | 방식 |
|---|---|
| `TopologyAssembler` | 순수 단위 테스트: 그룹핑, 엣지, docker-proxy 포트 매핑, 노출 범위 분류, 미확인 포트, 출처 부분 실패 |
| `DockerSource`, `MetricsSource` | MockWebServer와 실제 서버에서 떠 온 JSON fixture로 파싱 · 타임아웃 검증 |
| `HostSource` | 임시 디렉터리로 정상 · 파일 없음 · 깨진 JSON · STALE 검증 |
| `TopologyController` | `@WebMvcTest` 슬라이스로 응답 형태와 캐시 검증 |
| collector | `shellcheck`, 서버에서 1회 실행한 결과 확인 |
| 프론트엔드 | Vitest와 Testing Library로 응답→React Flow 변환, 배지 · 배너, 상세 패널, API 실패 시 마지막 그래프 유지 검증 |

레이아웃 좌표는 테스트하지 않는다.

**품질 게이트**: `./gradlew build`(테스트와 kotlinter 포함), `npm run typecheck && npm run lint && npm run test && npm run build`

## 9. 인수 기준 (EARS)

- **AC-01**: 사용자가 tailnet 기기에서 `http://<TAILNET_IP>:3001`을 열면, 시스템은 모든 compose 프로젝트와 그 컨테이너를 그룹으로 묶은 그래프를 보여주어야 한다.
- **AC-02**: 시스템은 각 컨테이너 노드에 상태(running/exited 등)와 health를 표시해야 한다.
- **AC-03**: 시스템은 각 컨테이너를 연결된 네트워크와 마운트된 볼륨에 엣지로 이어야 한다.
- **AC-04**: 호스트 포트가 컨테이너에 매핑되어 있으면, 시스템은 해당 리스닝 포트 노드를 컨테이너와 잇고 노출 범위(PUBLIC/TAILNET/LOCALHOST/INTERNAL/OTHER)를 색으로 표시해야 한다.
- **AC-05**: 시스템은 실행 중인 systemd 서비스와, 그 서비스가 연 리스닝 포트를 표시해야 한다.
- **AC-06**: Prometheus가 응답하면, 시스템은 컨테이너별 CPU % · 메모리와 호스트 CPU · 메모리 · 디스크 사용률을 표시해야 한다.
- **AC-07**: 사용자가 노드를 선택하면, 시스템은 상세 패널에 해당 노드의 속성과 연결 관계를 표시해야 한다.
- **AC-08**: 화면이 보이는 동안, 시스템은 15초마다 데이터를 갱신하고 마지막 갱신 시각을 표시해야 한다.
- **AC-09**: 한 출처가 실패하면, 시스템은 나머지 출처의 정보를 계속 표시하고 실패한 출처를 배지로 알려야 한다.
- **AC-10**: host.json이 90초보다 오래되면, 시스템은 호스트 출처를 STALE로 표시해야 한다.
- **AC-11**: 시스템은 Docker 상태를 바꾸는 요청을 보내지 않아야 하며, 프록시는 앱이 보낸 POST/DELETE 요청을 거부(403)해야 한다.
- **AC-12**: 시스템은 Tailscale IP 이외의 호스트 인터페이스에서 접근할 수 없어야 한다.

## 10. 나중에 할 일 (이번 범위 아님)

- 노출 범위 레인 보기(시안 C)
- Tailscale 피어 표시
- 이상 상태(컨테이너 exited, PUBLIC 포트 신규 등장) 알림
