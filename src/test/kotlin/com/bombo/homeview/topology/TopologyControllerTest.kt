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
class TopologyControllerTest(@Autowired private val mvc: MockMvcTester) {
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
