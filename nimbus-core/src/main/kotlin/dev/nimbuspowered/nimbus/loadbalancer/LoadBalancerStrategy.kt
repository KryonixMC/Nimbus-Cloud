package dev.nimbuspowered.nimbus.loadbalancer

import dev.nimbuspowered.nimbus.service.Service
import java.util.concurrent.atomic.AtomicInteger

interface LoadBalancerStrategy {
    fun select(candidates: List<Service>): Service
}

class LeastPlayersStrategy : LoadBalancerStrategy {
    override fun select(candidates: List<Service>): Service {
        return candidates.minByOrNull { it.playerCount } ?: candidates.first()
    }
}

class RoundRobinStrategy : LoadBalancerStrategy {
    private val counter = AtomicInteger(0)

    override fun select(candidates: List<Service>): Service {
        val index = Math.floorMod(counter.getAndIncrement(), candidates.size)
        return candidates[index]
    }
}
