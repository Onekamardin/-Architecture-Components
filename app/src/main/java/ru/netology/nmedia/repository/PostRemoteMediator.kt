package ru.netology.nmedia.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.entity.toEntity
import ru.netology.nmedia.error.ApiError
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val apiService: ApiService,
    private val db: AppDb,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
) : RemoteMediator<Int, PostEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        try {
            val response = when (loadType) {
                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.REFRESH -> {
                    val id = postRemoteKeyDao.max()
                    if (id == null) {
                        apiService.getLatest(state.config.initialLoadSize)
                    } else {
                        apiService.getAfter(id, state.config.pageSize)
                    }
                }
                LoadType.APPEND -> {
                    val id = postRemoteKeyDao.min()
                        ?: return MediatorResult.Success(endOfPaginationReached = false)
                    apiService.getBefore(id, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            db.withTransaction {
                if (body.isNotEmpty()) {
                    val firstId = body.first().id
                    val lastId = body.last().id

                    when (loadType) {
                        LoadType.REFRESH -> {
                            if (postRemoteKeyDao.max() == null) {
                                postRemoteKeyDao.insert(
                                    listOf(
                                        PostRemoteKeyEntity(type = PostRemoteKeyEntity.KeyType.BEFORE, id = lastId),
                                        PostRemoteKeyEntity(type = PostRemoteKeyEntity.KeyType.AFTER, id = firstId)
                                    )
                                )
                            } else {
                                postRemoteKeyDao.insert(
                                    PostRemoteKeyEntity(type = PostRemoteKeyEntity.KeyType.AFTER, id = firstId)
                                )
                            }
                        }
                        LoadType.APPEND -> {
                            postRemoteKeyDao.insert(
                                PostRemoteKeyEntity(type = PostRemoteKeyEntity.KeyType.BEFORE, id = lastId)
                            )
                        }
                        else -> {}
                    }
                    postDao.insert(body.toEntity())
                }
            }

            return MediatorResult.Success(endOfPaginationReached = body.isEmpty())
        } catch (e: IOException) {
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}
