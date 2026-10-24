package com.example.util.simpletimetracker.domain.recordType.repo

import com.example.util.simpletimetracker.domain.recordType.model.RecordType

interface RecordTypeRepo {

    suspend fun getAll(): List<RecordType>

    suspend fun get(id: Long): RecordType?

    suspend fun get(name: String): List<RecordType>

    suspend fun add(recordType: RecordType): Long

    suspend fun updateMetadata(recordType: RecordType)

    suspend fun rename(id: Long, name: String)

    suspend fun archive(id: Long)

    suspend fun restore(id: Long)

    suspend fun remove(id: Long)

    suspend fun clear()
}