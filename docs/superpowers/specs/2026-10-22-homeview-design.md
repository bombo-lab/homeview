# Homeview 설계 (SPEC-HV-001)

- 작성일: 2026-10-22
- 상태: 승인됨 (2026-10-27)
- 작성 규칙: STE100 원칙을 한국어에 적용한다 (0절 참조).
- HTML 뷰: `docs/superpowers/specs/2026-10-22-homeview-design.html`
- 레이아웃 시안: https://claude.ai/artifact/Ve7YcaVgX3SdXzPt6w4CJx (시안 A 채택)
- 그래프 비주얼 참고: `~/Documents/004_WORKSPACE/site` DomainGraph (5.2 참조)

## 0. 작성 규칙과 용어

### 0.1 작성 규칙

이 문서는 STE100 원칙을 따른다.

1. 문장 하나에 내용 하나만 쓴다.
2. 절차 문장은 20어절 이하로 쓴다. 설명 문장은 25어절 이하로 쓴다.
3. 능동태로 쓴다. 주어를 분명히 쓴다.
4. 절차는 번호 목록으로 쓴다. 단계 하나에 동작 하나만 쓴다.
5. 용어 하나에 뜻 하나만 쓴다. 0.2의 용어만 사용한다.
6. "등", "적절히", "필요 시" 같은 모호한 말은 쓰지 않는다.

### 0.2 용어

| 용어 | 뜻 |
|---|---|
| 홈서버 | bombo-server. Tailscale IP는 `<TAILNET_IP>`이다. |
| 앱 | `homeview-app` 컨테이너. 백엔드와 화면 파일을 포함한다. |
| 프록시 | `docker-socket-proxy` 컨테이너. Docker API 조회만 통과시킨다. |
| 수집기 | 홈서버의 systemd 타이머와 스크립트. 호스트 정보를 파일로 쓴다. |
| 출처 | 앱이 데이터를 읽는 곳. Docker, Prometheus, 호스트 파일의 세 개이다. |
| 호스트 파일 | 수집기가 쓰는 `/var/lib/homeview/host.json`. |
| 토폴로지 | 노드와 엣지로 된 그래프. 앱이 세 출처를 합쳐서 만든다. |
| 노드 | 토폴로지의 항목 하나. 예: 컨테이너, 네트워크, 포트. |
| 엣지 | 두 노드 사이의 관계 하나. 예: 컨테이너가 네트워크에 연결됨. |
| 노출 범위 | 포트에 접근할 수 있는 범위. 바인딩 주소로 판정한다. |
| 출처 상태 | 출처마다 하나씩 갖는 값. `OK`, `STALE`, `ERROR` 중 하나이다. |

## 1. 목적과 범위

### 1.1 목적

앱은 홈서버의 상태를 그래프로 보여준다. 앱은 홈서버에 연결된 인프라도 보여준다. 앱은 보기 전용이다.

### 1.2 범위에 포함

1. Docker 토폴로지: compose 프로젝트, 컨테이너, 네트워크, 볼륨, 포트와 그 관계.
2. 컨테이너 상태: 실행 상태(running, exited)와 health.
3. 메트릭: 컨테이너와 호스트의 CPU, 메모리, 디스크 현재 값.
4. 호스트 서비스: 실행 중인 systemd 서비스.
5. 리스닝 포트: 주소, 소유 프로세스, 노출 범위.

### 1.3 범위에서 제외

1. 제어 기능. 앱은 컨테이너를 시작, 중지, 재시작하지 않는다.
2. 로그 보기.
3. 앱 자체 인증. 앱은 Tailscale IP에만 포트를 연다.
4. 메트릭 추이 그래프. 앱은 Grafana 링크만 준다.
5. Tailscale 피어 목록.
6. 노출 범위 레인 보기(시안 C). 10절의 후보이다.

## 2. 전체 구조

### 2.1 구성도

```
[홈서버]
 ├─ 수집기 (systemd 타이머, 30초 간격)
 │    ss -tlnp 결과와 systemctl 결과 → 호스트 파일
 │
 └─ compose 프로젝트 "homeview"
      ├─ 프록시 ── /var/run/docker.sock (읽기 전용)
      │    허용: CONTAINERS, NETWORKS, VOLUMES 조회
      │    차단: POST, 그 외 모든 API
      │    네트워크: homeview_internal만. 호스트 포트 없음.
      │
      └─ 앱 (Spring Boot + React 정적 파일)
           ├─ DockerSource     → 프록시
           ├─ MetricsSource    → prometheus:9090
           ├─ HostSource       → 호스트 파일 (읽기 전용 마운트)
           ├─ TopologyAssembler
           └─ GET /api/topology, 화면
           포트: <TAILNET_IP>:3001 → 8080
```

### 2.2 결정 사항

1. 기존 `~/monitoring` compose 파일은 수정하지 않는다. 앱은 `monitoring_monitoring` 네트워크에 외부 네트워크로 연결한다.
2. 보기 전용 경계는 두 곳에 둔다. 첫째, 프록시가 POST를 차단한다. 둘째, 앱 코드에는 GET 요청만 있다.
3. 컨테이너에 `pid: host` 권한을 주지 않는다. 수집기가 호스트에서 호스트 정보를 수집한다.
4. 수집기는 임시 파일에 먼저 쓴다. 그 다음 `mv`로 호스트 파일을 교체한다. 그래서 앱은 반쯤 쓴 파일을 읽지 않는다.
5. 코드 저장소는 GitHub `bombo-lab/homeview`이다. 홈서버의 러너가 저장소를 받아 이미지를 빌드하고 배포한다 (7.2 참조).

## 3. 구성 요소

| 단위 | 책임 | 입력 | 출력 |
|---|---|---|---|
| `collector/homeview-collect.sh` | 호스트 정보를 수집한다. | `ss -tlnpH`, `systemctl list-units --type=service --state=running` | 호스트 파일 |
| `collector/homeview-collector.{service,timer}` | 30초마다 스크립트를 실행한다. | — | — |
| `DockerSource` | 프록시에서 Docker 정보를 읽는다. | `GET /containers/json?all=1`, `/networks`, `/volumes`, `/containers/{id}/json` | `DockerSnapshot` 또는 실패 |
| `MetricsSource` | Prometheus에서 현재 메트릭을 읽는다. | PromQL instant query | `MetricsSnapshot` 또는 실패 |
| `HostSource` | 호스트 파일을 읽는다. | 호스트 파일 | `HostSnapshot`, STALE, 실패 중 하나 |
| `TopologyAssembler` | 세 스냅샷을 토폴로지 하나로 합친다. 순수 함수이다. | 스냅샷 세 개, 출처 상태 세 개 | `Topology` |
| `TopologyController` | `GET /api/topology`를 제공한다. 결과를 5초 동안 캐시한다. | — | JSON |
| 화면 | 그래프, 상세 패널, 위쪽 바를 그린다. | `/api/topology` | 화면 |

### 3.1 호스트 파일 형식

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

### 3.2 PromQL

| 값 | 쿼리 |
|---|---|
| 컨테이너 CPU % | `sum by (name) (rate(container_cpu_usage_seconds_total{name!=""}[1m])) * 100` |
| 컨테이너 메모리 | `container_memory_working_set_bytes{name!=""}` |
| 컨테이너 메모리 제한 | `container_spec_memory_limit_bytes{name!=""}` |
| 호스트 CPU % | `100 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100` |
| 호스트 메모리 | `node_memory_MemTotal_bytes`, `node_memory_MemAvailable_bytes` |
| 호스트 디스크 `/` | `node_filesystem_size_bytes{mountpoint="/"}`, `node_filesystem_avail_bytes{mountpoint="/"}` |
| 가동 시간 | `time() - node_boot_time_seconds` |

## 4. 데이터 모델

### 4.1 노드

| kind | id 형식 | 출처 | 주요 속성 |
|---|---|---|---|
| `HOST` | `host:<hostname>` | 호스트 파일, node-exporter | 가동 시간, CPU, 메모리, 디스크 |
| `COMPOSE_PROJECT` | `compose:<project>` | 라벨 `com.docker.compose.project` | compose 파일 경로, 실행 수, 전체 수 |
| `CONTAINER` | `container:<name>` | Docker, cAdvisor | 이미지, 상태, health, 가동 시간, CPU %, 메모리, 메모리 제한 |
| `NETWORK` | `network:<name>` | Docker | 드라이버, 서브넷 |
| `VOLUME` | `volume:<name>` 또는 `bind:<host path>` | Docker | named 또는 bind |
| `HOST_SERVICE` | `service:<unit>` | 호스트 파일 | active 값, sub 값 |
| `LISTEN_PORT` | `port:<proto>:<address>:<port>` | 호스트 파일 | 소유 프로세스, 노출 범위 |

규칙:

1. `CONTAINER` 노드의 `parent`는 소속 compose 노드이다. 소속이 없으면 `parent`는 null이다.
2. 앱은 `host` 네트워크와 `none` 네트워크를 표시하지 않는다.
3. 앱은 연결된 컨테이너가 없는 네트워크를 표시하지 않는다.
4. 노드 id는 새로고침 후에도 바뀌지 않는다.

### 4.2 엣지

| kind | 시작 → 끝 | 속성 |
|---|---|---|
| `NETWORK_ATTACH` | CONTAINER → NETWORK | IP, alias |
| `MOUNT` | CONTAINER → VOLUME | 컨테이너 경로, 읽기 전용 여부 |
| `PORT_MAPPING` | LISTEN_PORT → CONTAINER | 컨테이너 포트, 노출 범위 |
| `PORT_OWNER` | LISTEN_PORT → HOST_SERVICE | — |

### 4.3 노출 범위

| 값 | 판정 기준 | 표시 색 |
|---|---|---|
| `PUBLIC` | `0.0.0.0`, `::`, `*` | 빨강 |
| `TAILNET` | `100.64.0.0/10`, `fd7a:115c:a1e0::/48` | 파랑 |
| `LOCALHOST` | `127.0.0.0/8`, `::1`. zone 표기(`127.0.0.53%lo`)를 포함한다. | 보라 |
| `INTERNAL` | 호스트 포트가 없다. 컨테이너가 포트를 노출만 한다. | 회색 |
| `OTHER` | 위 네 개에 해당하지 않는 주소. 예: LAN IP. | 주황 |

### 4.4 포트 연결 규칙

1. 소유 프로세스가 `docker-proxy`이면, 앱은 Docker 포트 매핑에서 같은 `(HostIp, PublicPort)`를 찾는다. 앱은 그 컨테이너에 `PORT_MAPPING` 엣지를 만든다.
2. 리스너에 `unit` 값이 있으면, 앱은 그 서비스에 `PORT_OWNER` 엣지를 만든다.
3. 소유 프로세스를 모르면, 앱은 `owner`를 `"UNKNOWN"`으로 설정한다. 화면은 "미확인"을 표시한다.

### 4.5 응답 예시

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

## 5. 화면 (시안 A: 그래프 중심)

### 5.1 위쪽 바

위쪽 바는 다음 항목을 표시한다.

1. 호스트 이름과 가동 시간.
2. 호스트 CPU, 메모리, 디스크 사용률.
3. 출처 상태 배지 세 개.
4. 마지막 갱신 시각("n초 전")과 새로고침 버튼.
5. 필터 세 개: 노출 범위, 볼륨 표시, 호스트 서비스 표시.

### 5.2 그래프 비주얼

참고 화면은 `~/Documents/004_WORKSPACE/site`의 DomainGraph(`src/pages/DomainGraph.tsx`)이다.

1. 화면은 SVG로 그래프를 직접 그린다. 그래프 라이브러리는 사용하지 않는다.
2. 배경색은 `#0a0a0b`이다. 화면은 배경에 24px 간격의 점 격자를 그린다.
3. 노드는 카드이다. 카드 크기는 200×72px이다. 모서리 반경은 10px이다.
4. 카드 배경색은 `#141416`이다. 카드 테두리색은 `#232328`이다.
5. 카드의 첫째 줄은 노드 이름이다. 첫째 줄은 13px 굵은 글자이다.
6. 카드의 둘째 줄은 노드 요약이다. 둘째 줄은 11px 흐린 글자(`#71717a`)이다. 요약 내용은 아래 표를 따른다.
7. 글자가 카드보다 길면, 화면은 글자를 말줄임표로 자른다.

| 노드 kind | 둘째 줄 |
|---|---|
| `HOST` | 가동 시간 |
| `HOST_SERVICE` | `sub` 값 |
| `LISTEN_PORT` | 주소와 노출 범위. 예: `<TAILNET_IP> · TAILNET` |
| `CONTAINER` | 상태 점, CPU %, 메모리. 예: `● 0.4% · 120MB` |
| `NETWORK` | 드라이버 |
| `VOLUME` | `named` 또는 bind 경로 |

컨테이너 상태 점의 색:

| 상태 | 색 |
|---|---|
| `RUNNING`, health가 `healthy` 또는 null | 초록 `#34d399` |
| health가 `starting` | 노랑 `#facc15` |
| health가 `unhealthy` | 빨강 `#f87171` |
| `RUNNING` 이외의 상태 | 회색 `#71717a` |

포트 카드의 테두리는 노출 범위 색이다 (4.3 참조). 노출 범위 색의 값:

| 노출 범위 | 색 |
|---|---|
| `PUBLIC` | `#f87171` |
| `TAILNET` | `#60a5fa` |
| `LOCALHOST` | `#c084fc` |
| `INTERNAL` | `#a1a1aa` |
| `OTHER` | `#fb923c` |

엣지는 3차 베지어 곡선이다. 곡선의 두 제어점은 시작점과 끝점의 중간 높이에 있다. 시작점과 끝점의 가로 거리가 50px보다 작으면, 화면은 제어점을 가로로 60px 벌린다.

| 엣지 kind | 색 | 선 모양 | 기본 불투명도 |
|---|---|---|---|
| `NETWORK_ATTACH` | 청록 `#22d3ee` | 실선 | 0.35 |
| `MOUNT` | 초록 `#34d399` | 실선. 읽기 전용이면 점선 `6 4`. | 0.35 |
| `PORT_MAPPING` | 노출 범위 색 | 실선 | 0.35 |
| `PORT_OWNER` | 노랑 `#facc15` | 점선 `3 3` | 0.25 |

엣지 선 두께는 1.5px이다.

### 5.3 배치

1. 화면은 노드를 섹션별 격자로 배치한다. 배치 라이브러리는 사용하지 않는다.
2. 섹션 하나는 노드 kind 하나 또는 compose 프로젝트 하나를 담는다.
3. 화면은 섹션을 위에서 아래로 다음 순서로 쌓는다.
   1. `HOST`
   2. `HOST_SERVICE`
   3. `LISTEN_PORT`
   4. compose 프로젝트. 프로젝트 이름 순서이다. 섹션은 소속 컨테이너를 담는다.
   5. 소속 없는 컨테이너 (`parent`가 null)
   6. `NETWORK`
   7. `VOLUME`
4. 섹션 하나는 최대 4열이다. 노드가 4개를 넘으면, 화면은 다음 줄에 배치한다.
5. 카드 간격은 가로 24px, 세로 20px이다. 섹션 안쪽 여백은 20px이다. 섹션 사이 간격은 40px이다.
6. 화면은 모든 섹션을 가장 넓은 섹션 기준으로 가운데 정렬한다.
7. 섹션 안에서 화면은 노드를 id 순서로 정렬한다. 그래서 같은 데이터는 항상 같은 위치에 그려진다.
8. 노드가 없는 섹션은 그리지 않는다. 필터(5.1)로 숨긴 노드도 섹션에서 뺀다.
9. 사용자는 노드를 드래그할 수 없다. 화면은 노드 위치를 저장하지 않는다.

섹션 박스:

1. 섹션 박스는 채우기가 없다. 테두리는 1px이고 불투명도는 0.15이다. 모서리 반경은 12px이다.
2. 섹션 박스의 왼쪽 위에 섹션 라벨을 쓴다. 라벨은 11px 굵은 대문자이다.
3. 섹션 박스의 오른쪽 위에 노드 개수를 쓴다. 개수의 불투명도는 0.4이다.
4. 테두리, 라벨, 개수는 섹션 색을 쓴다.

| 섹션 | 라벨 | 섹션 색 |
|---|---|---|
| `HOST` | `HOST` | `#e4e4e7` |
| `HOST_SERVICE` | `HOST SERVICES` | `#facc15` |
| `LISTEN_PORT` | `PORTS` | `#a1a1aa` |
| compose 프로젝트 | 프로젝트 이름 | `#3182f6` |
| 소속 없는 컨테이너 | `STANDALONE` | `#71717a` |
| `NETWORK` | `NETWORKS` | `#22d3ee` |
| `VOLUME` | `VOLUMES` | `#34d399` |

### 5.4 상호작용

1. 사용자는 배경을 드래그해서 그래프를 이동한다. 포인터가 3px보다 많이 움직이면, 화면은 그 동작을 드래그로 판정한다.
2. 사용자는 마우스 휠로 줌한다. 줌 중심은 포인터 위치이다. 줌 범위는 0.1배에서 3배이다.
3. 터치 기기에서 사용자는 한 손가락으로 이동한다. 사용자는 두 손가락으로 줌한다.
4. 화면은 오른쪽 위에 버튼 세 개를 표시한다: `+`, `−`, `Fit`. `+`는 1.2배 확대한다. `−`는 1.2배 축소한다.
5. `Fit`은 그래프 전체가 보이도록 줌과 위치를 맞춘다. 여백은 60px이다. 최대 배율은 1.5배이다.
6. 화면은 그래프를 처음 그릴 때 `Fit`을 한 번 실행한다.
7. 데이터가 갱신되면, 화면은 현재 줌과 위치를 유지한다.
8. 화면은 왼쪽 아래에 엣지 범례를 표시한다. 범례는 엣지 kind별 선 샘플과 이름을 보여준다.
9. 사용자가 노드를 선택하면, 화면은 선택된 카드의 테두리를 `#3182f6`으로 바꾼다.
10. 노드가 선택되면, 그 노드에 연결된 엣지의 불투명도는 기본값의 2.5배이다. 나머지 엣지의 불투명도는 기본값의 0.3배이다.
11. 사용자가 배경을 클릭하면, 화면은 선택을 해제한다. 드래그는 선택을 해제하지 않는다.
12. 데이터가 갱신되어도 선택된 노드가 남아 있으면, 화면은 선택을 유지한다.

### 5.5 상세 패널

1. 사용자가 노드를 선택하면, 오른쪽 패널이 그 노드의 속성을 표시한다. 패널 폭은 320px이다.
2. 패널의 맨 위에는 노드 kind와 노드 이름이 있다. kind는 섹션 색으로 표시한다.
3. 패널은 그 노드에 연결된 엣지도 표시한다. 엣지 한 줄은 엣지 kind 배지와 상대 노드 이름이다.
4. 사용자가 엣지 줄을 클릭하면, 화면은 상대 노드를 선택한다.
5. 컨테이너 노드와 호스트 노드의 패널에는 Grafana 링크(`http://<TAILNET_IP>:3000`)가 있다.
6. 화면 폭이 640px 이하이면, 패널은 화면 아래에서 올라오는 시트로 바뀐다.

## 6. 갱신과 오류 처리

### 6.1 갱신 주기

| 주체 | 동작 | 주기 |
|---|---|---|
| 수집기 | 호스트 파일을 다시 쓴다. | 30초 |
| Prometheus | 메트릭을 수집한다 (기존 설정). | 15초 |
| 화면 | `/api/topology`를 요청한다. | 15초 |
| 앱 | 결과를 캐시한다. | 5초 |
| 앱 | 호스트 파일의 나이를 검사한다. | 요청마다 |

규칙:

1. 탭이 숨겨지면, 화면은 요청을 멈춘다.
2. 탭이 다시 보이면, 화면은 즉시 한 번 요청한다.
3. 앱은 세 출처를 병렬로 조회한다.
4. 출처별 HTTP 타임아웃은 3초이다.

### 6.2 출처 상태 판정

1. 앱이 출처를 조회한다.
2. 출처가 3초 안에 응답하지 않으면, 출처 상태는 `ERROR`이다.
3. 응답을 해석하지 못하면, 출처 상태는 `ERROR`이다.
4. 호스트 파일의 `snapshotAt`이 90초보다 오래되었으면, 출처 상태는 `STALE`이다. 앱은 그 데이터를 계속 사용한다.
5. 그 외에는 출처 상태가 `OK`이다.

### 6.3 실패 시 동작

| 상황 | 앱의 동작 | 화면의 동작 |
|---|---|---|
| 프록시 실패 | `docker`를 `ERROR`로 설정한다. Docker 노드를 만들지 않는다. | 빨간 배너를 표시한다. 호스트 노드는 계속 표시한다. |
| Prometheus 실패 | `prometheus`를 `ERROR`로 설정한다. metrics를 null로 설정한다. | 수치 자리에 "—"를 표시한다. |
| 호스트 파일이 오래됨 | `host`를 `STALE`로 설정한다. | 노란 배지에 "n분 전"을 표시한다. |
| 호스트 파일이 없거나 깨짐 | `host`를 `ERROR`로 설정한다. 서비스 노드와 포트 노드를 만들지 않는다. | 빨간 배지를 표시한다. |
| API 실패 | — | 마지막 그래프를 유지한다. "연결 끊김 · n초 전 데이터"를 표시한다. |

## 7. 배포

### 7.1 설정 값

| 항목 | 값 |
|---|---|
| compose 프로젝트 이름 | `homeview` |
| 배포 작업 디렉터리 | 러너 작업 디렉터리. compose 프로젝트 이름으로 구분한다. |
| 네트워크 | `homeview_internal` (프록시, 앱), `monitoring_monitoring` (external, 앱만) |
| 포트 | `<TAILNET_IP>:3001:8080` |
| 마운트 | `/var/lib/homeview:/data:ro`. 앱은 `/data/host.json`을 읽는다. |
| 메모리 제한 | 앱 `512m`, 프록시 `64m` |

앱은 호스트 파일이 아니라 디렉터리를 마운트한다. 수집기는 `mv`로 파일을 교체한다. 파일 하나만 마운트하면, 컨테이너는 교체 전 파일을 계속 읽는다.

### 7.2 이미지 배포 절차

배포는 GitHub Actions `cd` 워크플로가 한다. 홈서버의 조직 러너(라벨 `homeserver`)가 실행한다.

1. 사용자가 `cd` 워크플로를 수동 실행한다. 입력 `server`에서 배포할 서버를 고른다.
2. 러너가 `docker/Dockerfile`의 `build` 스테이지를 실행한다. 이 스테이지는 화면 검사와 `gradle build`를 포함한다.
3. 러너가 `runtime` 이미지를 커밋 SHA 태그와 `latest` 태그로 빌드한다.
4. 러너가 `docker compose -f deploy/compose.yml up -d --wait`를 실행한다.

레지스트리는 사용하지 않는다. 롤백은 `IMAGE_TAG=<이전 커밋 SHA> docker compose -f deploy/compose.yml up -d --wait`로 한다.

### 7.3 수집기 설치 절차

1. 스크립트를 `/usr/local/bin/homeview-collect.sh`에 복사한다.
2. 유닛 파일을 `/etc/systemd/system/homeview-collector.service`와 `.timer`에 복사한다.
3. `systemctl daemon-reload`를 실행한다.
4. `systemctl enable --now homeview-collector.timer`를 실행한다.

수집기는 root로 실행한다. 다른 사용자의 포트 소유 프로세스를 보려면 `ss -p`에 root 권한이 필요하다. 호스트 파일의 권한은 `0644`이다.

## 8. 테스트

### 8.1 규칙

테스트 코드는 `kotlin-junit5-test.md`를 따른다.

1. 식별자는 ASCII로 쓴다.
2. 한글은 `@DisplayName`에만 쓴다.
3. 분기는 `@Nested inner class`로 나눈다.

### 8.2 대상과 방식

| 대상 | 방식 | 검증 항목 |
|---|---|---|
| `TopologyAssembler` | 순수 단위 테스트 | 그룹, 엣지, docker-proxy 포트 연결, 노출 범위, 미확인 포트, 출처 부분 실패 |
| `DockerSource`, `MetricsSource` | MockWebServer. fixture는 홈서버에서 받은 실제 JSON이다. | 파싱, 3초 타임아웃 |
| `HostSource` | 임시 디렉터리 | 정상, 파일 없음, 깨진 JSON, STALE |
| `TopologyController` | `@WebMvcTest` 슬라이스 | 응답 형태, 5초 캐시 |
| 수집기 | `shellcheck`, 홈서버에서 1회 실행 | 문법, 출력 형식 |
| 화면 | Vitest, Testing Library | 응답 변환, 배지, 배너, 상세 패널, 선택 강조, API 실패 시 마지막 그래프 유지 |
| 섹션 배치 함수 | Vitest 순수 단위 테스트 | 섹션 순서, 4열 줄바꿈, 빈 섹션 제외, 같은 입력에 같은 좌표 |

픽셀 단위 렌더링 결과는 테스트하지 않는다.

### 8.3 품질 게이트

1. `./gradlew build`를 실행한다. 이 명령은 테스트와 kotlinter를 포함한다.
2. `npm run typecheck`를 실행한다.
3. `npm run lint`를 실행한다.
4. `npm run test`를 실행한다.
5. `npm run build`를 실행한다.

## 9. 인수 기준 (EARS)

- **AC-01**: 사용자가 tailnet 기기에서 `http://<TAILNET_IP>:3001`을 열면, 앱은 compose 프로젝트별로 컨테이너를 묶은 그래프를 표시해야 한다.
- **AC-02**: 앱은 각 컨테이너 노드에 실행 상태와 health를 표시해야 한다.
- **AC-03**: 앱은 각 컨테이너를 연결된 네트워크와 마운트된 볼륨에 엣지로 이어야 한다.
- **AC-04**: 호스트 포트가 컨테이너에 매핑되어 있으면, 앱은 포트 노드를 그 컨테이너에 잇고 노출 범위 색을 표시해야 한다.
- **AC-05**: 앱은 실행 중인 systemd 서비스와 그 서비스의 리스닝 포트를 표시해야 한다.
- **AC-06**: Prometheus가 응답하면, 앱은 컨테이너별 CPU %와 메모리를 표시해야 한다. 앱은 호스트 CPU, 메모리, 디스크 사용률도 표시해야 한다.
- **AC-07**: 사용자가 노드를 선택하면, 앱은 상세 패널에 그 노드의 속성과 연결 관계를 표시해야 한다.
- **AC-08**: 화면이 보이는 동안, 앱은 15초마다 데이터를 갱신해야 한다. 앱은 마지막 갱신 시각을 표시해야 한다.
- **AC-09**: 출처 하나가 실패하면, 앱은 나머지 출처의 정보를 계속 표시해야 한다. 앱은 실패한 출처를 배지로 표시해야 한다.
- **AC-10**: 호스트 파일이 90초보다 오래되면, 앱은 호스트 출처를 `STALE`로 표시해야 한다.
- **AC-11**: 앱은 Docker 상태를 바꾸는 요청을 보내지 않아야 한다. 프록시는 POST 요청과 DELETE 요청에 403을 반환해야 한다.
- **AC-12**: 앱은 Tailscale IP 이외의 인터페이스에서 접근할 수 없어야 한다.

## 10. 후보 (이번 범위 아님)

1. 노출 범위 레인 보기 (시안 C).
2. Tailscale 피어 표시.
3. 이상 상태 알림. 예: 컨테이너가 exited로 바뀜, 새 PUBLIC 포트가 생김.
