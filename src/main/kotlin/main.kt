import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import ru.netology.coroutines.dto.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val gson = Gson()
private const val BASE_URL = "http://localhost:9999"  // Для macOS
private const val SLOW_API = "$BASE_URL/api/slow"

private val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor(::println).apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()


suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCancellableCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}


suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url).use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${response.message}")
            }
            val body = requireNotNull(response.body) { "Response body is null" }
            gson.fromJson(body.string(), typeToken.type)
        }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$SLOW_API/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$SLOW_API/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})


fun createAuthorFromPost(post: Post): Author = Author(
    id = post.id,  // Используем ID поста как временный ID автора
    name = post.author,
    avatar = post.authorAvatar
)


fun createAuthorFromComment(comment: Comment): Author = Author(
    id = comment.id,  // Используем ID комментария как временный ID автора
    name = comment.author,
    avatar = comment.authorAvatar
)


suspend fun loadPostsWithAuthors(client: OkHttpClient): List<PostWithCommentsAndAuthors> = coroutineScope {
    println("🔍 Загрузка постов...")
    val posts = getPosts(client)
    println("✅ Загружено постов: ${posts.size}")

    posts.map { post ->
        async {
            println("  📦 Обработка поста #${post.id} от '${post.author}'")

            // Создаем автора из данных поста
            val postAuthor = createAuthorFromPost(post)
            val postWithAuthor = PostWithAuthor(post, postAuthor)

            // Загружаем комментарии
            println("  💬 Загрузка комментариев к посту #${post.id}...")
            val comments = try {
                getComments(client, post.id)
            } catch (e: Exception) {
                println("  ⚠️ Ошибка загрузки комментариев: ${e.message}")
                emptyList()
            }

            println("  ✅ Загружено комментариев: ${comments.size}")

            // Для каждого комментария создаем автора
            val commentsWithAuthors = comments.map { comment ->
                async {
                    val commentAuthor = createAuthorFromComment(comment)
                    CommentWithAuthor(comment, commentAuthor)
                }
            }.awaitAll()

            PostWithCommentsAndAuthors(postWithAuthor, commentsWithAuthors)
        }
    }.awaitAll()
}

fun main() = runBlocking {
    println("╔══════════════════════════════════════════════════════╗")
    println("║     🚀 ЗАГРУЗКА ПОСТОВ С АВТОРАМИ И КОММЕНТАРИЯМИ   ║")
    println("╚══════════════════════════════════════════════════════╝")
    println("📡 Сервер: $BASE_URL")
    println()

    try {
        val startTime = System.currentTimeMillis()
        val postsWithAuthors = loadPostsWithAuthors(client)
        val endTime = System.currentTimeMillis()

        println("\n" + "═".repeat(60))
        println("✅ ЗАГРУЗКА ЗАВЕРШЕНА ЗА ${endTime - startTime}мс")
        println("📊 Всего обработано постов: ${postsWithAuthors.size}")
        println("═".repeat(60))
        println()

        if (postsWithAuthors.isEmpty()) {
            println("⚠️ Нет постов для отображения")
        } else {
            postsWithAuthors.forEachIndexed { index, postWithComments ->
                println("\n📌 ПОСТ #${index + 1} (ID: ${postWithComments.postWithAuthor.post.id})")
                println("┈" .repeat(50))
                println("👤 Автор: ${postWithComments.postWithAuthor.author.name}")
                println("🖼️  Аватар: ${postWithComments.postWithAuthor.author.avatar}")
                println("📝 Содержание:")
                println("   ${postWithComments.postWithAuthor.post.content}")
                println("❤️  Лайков: ${postWithComments.postWithAuthor.post.likes}")

                // Вложение, если есть
                postWithComments.postWithAuthor.post.attachment?.let { attachment ->
                    println("📎 Вложение: ${attachment.url}")
                    println("📄 Описание: ${attachment.description}")
                    println("🏷️  Тип: ${attachment.type}")
                }

                println("\n💬 КОММЕНТАРИИ (${postWithComments.commentsWithAuthors.size}):")

                if (postWithComments.commentsWithAuthors.isEmpty()) {
                    println("   Нет комментариев")
                } else {
                    postWithComments.commentsWithAuthors.forEachIndexed { commentIndex, commentWithAuthor ->
                        println("   ${commentIndex + 1}. ${commentWithAuthor.author.name}:")
                        println("      \"${commentWithAuthor.comment.content}\"")
                        println("      ❤️ ${commentWithAuthor.comment.likes}")
                    }
                }
                println("┈" .repeat(50))
            }
        }

        println("\n🎉 Программа успешно завершена!")

    } catch (e: Exception) {
        println("\n❌ ОШИБКА: ${e.message}")
        println("\n🔍 Детали ошибки:")
        e.printStackTrace()
    }
}