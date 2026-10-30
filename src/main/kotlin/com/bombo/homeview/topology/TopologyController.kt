package com.bombo.homeview.topology

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime

@RestController
class TopologyController(private val clock: Clock) {
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
