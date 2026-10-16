package com.safebrowse.database.dao

import androidx.room.*
import com.safebrowse.database.entity.BlockedDomain
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedDomainDao {

    @Query("SELECT * FROM blocked_domains ORDER BY domain ASC")
    fun observeAll(): Flow<List<BlockedDomain>>

    /** Live stream the VPN service subscribes to so its cache never goes stale. */
    @Query("SELECT domain FROM blocked_domains")
    fun observeDomains(): Flow<List<String>>

    @Query("SELECT domain FROM blocked_domains")
    suspend fun getDomains(): List<String>

    @Query("SELECT * FROM blocked_domains WHERE domain LIKE '%' || :query || '%' ORDER BY domain ASC")
    fun search(query: String): Flow<List<BlockedDomain>>

    @Query("SELECT COUNT(*) FROM blocked_domains")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE domain = :domain")
    suspend fun existsCount(domain: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(domain: BlockedDomain): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(domains: List<BlockedDomain>)

    /** The only delete path in the app. Reached only after the gate passes. */
    @Query("DELETE FROM blocked_domains WHERE id = :id")
    suspend fun deleteById(id: Long)
}
