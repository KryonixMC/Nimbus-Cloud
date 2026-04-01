package dev.kryonix.nimbus.cluster

import java.util.concurrent.atomic.AtomicInteger

interface PlacementStrategy {
    fun select(candidates: List<NodeConnection>): NodeConnection
}

class LeastServicesPlacement : PlacementStrategy {
    override fun select(candidates: List<NodeConnection>): NodeConnection {
        return candidates.minByOrNull { it.currentServices } ?: candidates.first()
    }
}

class LeastMemoryPlacement : PlacementStrategy {
    override fun select(candidates: List<NodeConnection>): NodeConnection {
        return candidates.minByOrNull { it.memoryUsedMb } ?: candidates.first()
    }
}

class RoundRobinPlacement : PlacementStrategy {
    private val counter = AtomicInteger(0)

    override fun select(candidates: List<NodeConnection>): NodeConnection {
        val index = counter.getAndIncrement() % candidates.size
        return candidates[index]
    }
}
