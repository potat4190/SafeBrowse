package com.safebrowse.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One blocked domain, always stored normalized and lowercase.
 *
 * There is deliberately no "enabled" flag. A disable toggle would be an
 * ungated way to unblock a site, which is exactly what the reading gate
 * exists to prevent. A domain is either on the list or it is not.
 */
@Entity(
    tableName = "blocked_domains",
    indices = [Index(value = ["domain"], unique = true), Index(value = ["is_default"])]
)
data class BlockedDomain(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false
)
