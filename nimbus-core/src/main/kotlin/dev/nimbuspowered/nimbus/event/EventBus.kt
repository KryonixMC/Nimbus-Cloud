package dev.nimbuspowered.nimbus.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class EventBus(@PublishedApi internal val scope: CoroutineScope) {

    @PublishedApi
    internal val logger = LoggerFactory.getLogger(EventBus::class.java)

    @PublishedApi
    internal val _events = MutableSharedFlow<NimbusEvent>(extraBufferCapacity = 512)

    suspend fun emit(event: NimbusEvent) {
        logger.debug("Event emitted: {}", event)
        _events.emit(event)
    }

    fun subscribe(): SharedFlow<NimbusEvent> = _events.asSharedFlow()

    inline fun <reified T : NimbusEvent> on(noinline handler: suspend (T) -> Unit): Job {
        return scope.launch {
            _events.filterIsInstance<T>().collect { event ->
                try {
                    handler(event)
                } catch (e: Exception) {
                    logger.error("Event handler failed for {}: {}", event::class.simpleName, e.message, e)
                }
            }
        }
    }
}
