package com.polina.memify

import com.polina.memify.db.Templates
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

// Источник шаблонов — Imgflip Meme Generator API (https://imgflip.com/api), эндпоинт
// GET /get_memes. Бесплатный, без авторизации, отдаёт шаблоны в порядке от самых
// "заподписанных" за последние 30 дней к менее популярным — то есть уже актуальный,
// живой топ, а не статичный список.
//
// Раньше строки в Templates появлялись вручную/из старой Firestore-выгрузки. Теперь эта
// таблица регулярно синхронизируется с Imgflip (см. configureTemplatesSync ниже), а весь
// остальной код — TemplatesRoutes.kt (сортировка best/new, избранное, toggle-like) и схема
// в Tables.kt — не менялся: он как читал/писал Templates/TemplateFavourites, так и читает/
// пишет, просто теперь данные в Templates берутся не вручную, а из Imgflip.

private const val IMGFLIP_GET_MEMES_URL = "https://api.imgflip.com/get_memes"
private const val TEMPLATES_SYNC_INTERVAL_MS = 15 * 60 * 1000L // раз в 15 минут

@Serializable
private data class ImgflipResponse(
    val success: Boolean,
    val data: ImgflipData? = null,
)

@Serializable
private data class ImgflipData(
    val memes: List<ImgflipMeme> = emptyList(),
)

@Serializable
private data class ImgflipMeme(
    val id: String,
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
)

private val imgflipHttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

private suspend fun fetchImgflipTemplates(): List<ImgflipMeme> {
    val response: ImgflipResponse = imgflipHttpClient.get(IMGFLIP_GET_MEMES_URL).body()
    if (!response.success) return emptyList()
    return response.data?.memes.orEmpty()
}

/**
 * Затягивает актуальный список шаблонов с Imgflip и обновляет ими таблицу Templates
 * (insert для новых id, update для уже известных). Ничего не удаляет — если в таблице
 * были другие шаблоны, они останутся (в т.ч. чтобы не сломать ссылки из
 * TemplateFavourites/Posts на template_id).
 *
 * @return сколько шаблонов вернул Imgflip
 */
suspend fun syncTemplatesFromImgflip(): Int {
    val memes = fetchImgflipTemplates()
    if (memes.isEmpty()) return 0

    val ids = memes.map { "imgflip-${it.id}" }
    val total = memes.size

    transaction {
        val existingIds =
            Templates.selectAll()
                .where { Templates.id inList ids }
                .map { it[Templates.id] }
                .toSet()

        memes.forEachIndexed { index, meme ->
            val templateId = "imgflip-${meme.id}"
            // Imgflip уже отдаёт мемы от самого популярного к менее популярному —
            // переносим этот порядок в usedCount, чтобы ?sort=best (сортировка по
            // usedCount) отражала актуальный рейтинг Imgflip.
            val rank = total - index

            if (templateId in existingIds) {
                Templates.update({ Templates.id eq templateId }) {
                    it[name] = meme.name
                    it[url] = meme.url
                    it[width] = meme.width
                    it[height] = meme.height
                    it[usedCount] = rank
                    // createdAt намеренно не трогаем, иначе при каждой синхронизации
                    // шаблон "молодел бы" и портил ?sort=new.
                }
            } else {
                Templates.insert {
                    it[id] = templateId
                    it[name] = meme.name
                    it[url] = meme.url
                    it[width] = meme.width
                    it[height] = meme.height
                    it[usedCount] = rank
                }
            }
        }
    }

    return memes.size
}

fun Application.configureTemplatesSync() {
    launch {
        syncSafely()
        while (isActive) {
            delay(TEMPLATES_SYNC_INTERVAL_MS)
            syncSafely()
        }
    }
}

private suspend fun syncSafely() {
    try {
        val count = syncTemplatesFromImgflip()
        println("[templates-sync] Синхронизировано с Imgflip: $count шаблонов")
    } catch (e: Exception) {
        println("[templates-sync] Не удалось синхронизировать шаблоны с Imgflip: ${e.message}")
    }
}
