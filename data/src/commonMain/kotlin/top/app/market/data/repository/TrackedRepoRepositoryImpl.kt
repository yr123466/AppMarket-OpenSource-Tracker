package top.app.market.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.preferences.OpenSourcePreferenceKeys
import top.app.market.data.platform.debugLog
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.tracked.TrackedRepo
import top.app.market.domain.repository.TrackedRepoRepository

/**
 * 「包名 → 开源仓库」跟踪表，持久化在偏好存储的单个 JSON 数组里。
 *
 * GitHub / GitLab 都拿不到「某个已安装包名对应哪个仓库」，所以这张表是这两个源
 * 做更新检查的前提。写入时机由仓库层控制：用户查看某个开源应用详情、或直接粘贴
 * 仓库链接时自动登记，无需额外的「添加」界面。
 */
internal class TrackedRepoRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val json: Json,
) : TrackedRepoRepository {

    private val mutex = Mutex()

    override suspend fun tracked(source: AppSource): List<TrackedRepo> =
        readAll().filter { it.source == source }

    override suspend fun track(repo: TrackedRepo) = mutex.withLock {
        if (repo.packageName.isBlank() || repo.slug.isBlank()) return@withLock
        val current = readAll()
        val existing = current.firstOrNull { it.packageName == repo.packageName && it.source == repo.source }
        val addedAt = existing?.addedAt ?: repo.addedAt.takeIf { it > 0L }
            ?: Clock.System.now().toEpochMilliseconds()
        val next = current.filterNot { it.packageName == repo.packageName && it.source == repo.source } +
            repo.copy(
                addedAt = addedAt,
                // 后续登记可能缺少某些字段（例如只从链接解析出 slug），保留已有值不覆盖
                appId = repo.appId.takeIf { it > 0L } ?: existing?.appId ?: 0L,
                displayName = repo.displayName.ifBlank { existing?.displayName.orEmpty() },
                icon = repo.icon.ifBlank { existing?.icon.orEmpty() },
            )
        writeAll(next)
    }

    override suspend fun untrack(packageName: String, source: AppSource) = mutex.withLock {
        writeAll(readAll().filterNot { it.packageName == packageName && it.source == source })
    }

    override suspend fun find(packageName: String, source: AppSource): TrackedRepo? =
        readAll().firstOrNull { it.packageName == packageName && it.source == source }

    private suspend fun readAll(): List<TrackedRepo> {
        val raw = try {
            preferences.read(OpenSourcePreferenceKeys.TrackedRepos)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            debugLog("TrackedRepo") { "Failed to read tracked repos: ${error.message}" }
            return emptyList()
        }?.takeIf { it.isNotBlank() } ?: return emptyList()

        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val packageName = obj.string("packageName")
            val slug = obj.string("slug")
            val source = AppSource.fromToken(obj.string("source")) ?: return@mapNotNull null
            if (packageName.isBlank() || slug.isBlank()) return@mapNotNull null
            TrackedRepo(
                packageName = packageName,
                slug = slug,
                source = source,
                appId = obj.string("appId").toLongOrNull() ?: 0L,
                displayName = obj.string("displayName"),
                icon = obj.string("icon"),
                addedAt = obj.string("addedAt").toLongOrNull() ?: 0L,
            )
        }
    }

    private suspend fun writeAll(repos: List<TrackedRepo>) {
        val array = buildJsonArray {
            repos.sortedBy { it.packageName }.forEach { repo ->
                add(
                    buildJsonObject {
                        put("packageName", repo.packageName)
                        put("slug", repo.slug)
                        put("source", repo.source.token)
                        put("appId", repo.appId)
                        put("displayName", repo.displayName)
                        put("icon", repo.icon)
                        put("addedAt", repo.addedAt)
                    }
                )
            }
        }
        preferences.put(OpenSourcePreferenceKeys.TrackedRepos, array.toString())
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
