package com.druk.lmplayground.settings

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import kotlinx.coroutines.launch
import java.util.UUID

class SystemPromptsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: SystemPromptRepository? =
        (app as? App)?.systemPromptRepository

    val prompts: LiveData<List<SystemPromptEntity>> =
        repository?.getAll() ?: MutableLiveData(emptyList())

    @MainThread
    fun addPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            repository?.insert(
                SystemPromptEntity(
                    id = UUID.randomUUID().toString(),
                    text = trimmed,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    @MainThread
    fun updatePrompt(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            deletePrompt(id)
            return
        }
        viewModelScope.launch {
            val existing = repository?.getById(id) ?: return@launch
            repository.update(
                existing.copy(text = trimmed, updatedAt = System.currentTimeMillis())
            )
        }
    }

    @MainThread
    fun deletePrompt(id: String) {
        viewModelScope.launch {
            repository?.delete(id)
        }
    }
}
