package com.bombo.homeview.topology

import java.time.OffsetDateTime

enum class SourceStatus { OK, STALE, ERROR }

data class SourceState(val status: SourceStatus, val snapshotAt: OffsetDateTime? = null, val message: String? = null)

data class Sources(val docker: SourceState, val prometheus: SourceState, val host: SourceState)

enum class NodeKind { HOST, COMPOSE_PROJECT, CONTAINER, NETWORK, VOLUME, HOST_SERVICE, LISTEN_PORT }

enum class EdgeKind { NETWORK_ATTACH, MOUNT, PORT_MAPPING, PORT_OWNER }

data class NodeMetrics(val cpuPercent: Double?, val memBytes: Long?, val memLimitBytes: Long?)

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
