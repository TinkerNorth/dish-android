package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.data.model.ControllerEntry
import com.tinkernorth.dish.data.repository.ControllerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControllerManager @Inject constructor(
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository
) {

    private val _controllers = MutableStateFlow<List<ControllerEntry>>(emptyList())
    val controllers: StateFlow<List<ControllerEntry>> = _controllers.asStateFlow()

    private val disconnectJobs = mutableMapOf<Int, Job>()

    fun addController(id: Int, name: String) {
        disconnectJobs[id]?.cancel()
        disconnectJobs.remove(id)

        _controllers.update { current ->
            if (current.any { it.id == id }) {
                current.map { if (it.id == id) it.copy(isDisconnected = false, disconnectTimeLeft = 0) else it }
            } else {
                val newIndex = (current.maxOfOrNull { it.controllerIndex } ?: -1) + 1
                current + ControllerEntry(id, name, controllerIndex = newIndex)
            }
        }

        val entry = _controllers.value.find { it.id == id } ?: return

        scope.launch {
            controllerRepo.resetControllerAck()
            controllerRepo.addController(entry.controllerIndex, 0x0003) // Default capabilities

            val ack = withContext(Dispatchers.IO) {
                var a = -1
                for (i in 0 until 20) {
                    a = controllerRepo.getLastControllerAck()
                    if (a != -1) break
                    delay(100)
                }
                a
            }

            if (ack != -1) {
                controllerRepo.sendControllerType(entry.controllerIndex, 0) // Default type
            }
        }
    }

    fun removeController(id: Int) {
        _controllers.update { current ->
            current.map {
                if (it.id == id) it.copy(isDisconnected = true, disconnectTimeLeft = 30) else it
            }
        }

        disconnectJobs[id]?.cancel()
        disconnectJobs[id] = scope.launch {
            for (i in 29 downTo 0) {
                delay(1000)
                _controllers.update { current ->
                    current.map {
                        if (it.id == id) it.copy(disconnectTimeLeft = i) else it
                    }
                }
            }
            // Actually remove after timeout
            val entry = _controllers.value.find { it.id == id }
            _controllers.update { current -> current.filter { it.id != id } }
            entry?.let {
                controllerRepo.removeController(it.controllerIndex)
            }
        }
    }
}
