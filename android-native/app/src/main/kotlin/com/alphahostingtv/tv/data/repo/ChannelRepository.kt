package com.alphahostingtv.tv.data.repo

import com.alphahostingtv.tv.data.db.ChannelDao
import com.alphahostingtv.tv.data.db.ChannelEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val dao: ChannelDao,
) {
    fun observeForProvider(pid: Long): Flow<List<ChannelEntity>> = dao.observeForProvider(pid)
    suspend fun byId(id: Long): ChannelEntity? = dao.byId(id)
}
