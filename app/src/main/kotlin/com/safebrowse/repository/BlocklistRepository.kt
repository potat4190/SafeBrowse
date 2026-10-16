package com.safebrowse.repository

import android.content.Context
import android.util.Log
import com.safebrowse.database.dao.BlockedDomainDao
import com.safebrowse.database.entity.BlockedDomain
import com.safebrowse.settings.AppPreferences
import com.safebrowse.util.DomainNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocklistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: BlockedDomainDao,
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "BlocklistRepository"
        private const val DEFAULT_ASSET = "default_blocklist.txt"
        private const val BATCH_SIZE = 500
    }

    fun observeAll(): Flow<List<BlockedDomain>> = dao.observeAll()
    fun search(query: String): Flow<List<BlockedDomain>> = dao.search(query)
    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    /** Load the bundled starter list once, on first launch. Idempotent. */
    suspend fun seedDefaultBlocklistIfNeeded() = withContext(Dispatchers.IO) {
        if (preferences.isDefaultListLoaded()) return@withContext
        try {
            val domains = context.assets.open(DEFAULT_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { DomainNormalizer.normalize(it) }
                    .distinct()
                    .map { BlockedDomain(domain = it, isDefault = true) }
                    .toList()
            }
            domains.chunked(BATCH_SIZE).forEach { dao.insertAll(it) }
            preferences.setDefaultListLoaded(true)
            Log.i(TAG, "Seeded ${domains.size} default domains")
        } catch (e: Exception) {
            Log.e(TAG, "Could not seed the default blocklist", e)
        }
    }

    suspend fun addDomain(input: String): AddResult = withContext(Dispatchers.IO) {
        val normalized = DomainNormalizer.normalize(input)
            ?: return@withContext AddResult.InvalidDomain
        if (dao.existsCount(normalized) > 0) return@withContext AddResult.AlreadyExists
        val id = dao.insert(BlockedDomain(domain = normalized))
        if (id > 0) AddResult.Success(normalized) else AddResult.AlreadyExists
    }

    /** Only ever called from the gate. There is no other removal path. */
    suspend fun removeDomainById(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun importDomains(text: String): Int = withContext(Dispatchers.IO) {
        val domains = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { DomainNormalizer.normalize(it) }
            .distinct()
        domains.chunked(BATCH_SIZE).forEach { batch ->
            dao.insertAll(batch.map { BlockedDomain(domain = it) })
        }
        domains.size
    }

    sealed class AddResult {
        data class Success(val domain: String) : AddResult()
        data object InvalidDomain : AddResult()
        data object AlreadyExists : AddResult()
    }
}
