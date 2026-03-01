import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import ru.netology.coroutines.dto.Author
import ru.netology.coroutines.dto.Comment
import ru.netology.coroutines.dto.Post
import java.io.IOException
import java.util.concurrent.TimeUnit

private val gson = Gson()
private const val BASE_URL = "http://localhost:9999"
private const val API_POSTS = "$BASE_URL/api/slow/posts"
private const val API_COMMENTS = "$BASE_URL/api/slow/posts/{postId}/comments"
private const val API_AUTHOR = "$BASE_URL/api/authors/{authorId}"

private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${response.message}")
            }
            val body = requireNotNull(response.body) { "Response body is null" }
            gson.fromJson(body.string(), typeToken.type)
        }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest(API_POSTS, client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, postId: Long): List<Comment> =
    makeRequest("$API_COMMENTS".replace("{postId}", postId.toString()), client, object : TypeToken<List<Comment>>() {})

suspend fun getAuthor(client: OkHttpClient, authorId: Long): Author =
    makeRequest("$API_AUTHOR".replace("{authorId}", authorId.toString()), client, object : TypeToken<Author>() {})

data class PostWithAuthor(
    val post: Post,
    val author: Author
)

data class CommentWithAuthor(
    val comment: Comment,
    val author: Author
)

data class PostWithCommentsAndAuthors(
    val postWithAuthor: PostWithAuthor,
    val commentsWithAuthors: List<CommentWithAuthor>
)

suspend fun loadPostsWithAuthorsAndComments(client: OkHttpClient): List<PostWithCommentsAndAuthors> = coroutineScope {
    // 1. Загружаем все посты
    val posts = getPosts(client)
    println("Загружено постов: ${posts.size}")

    // 2. Для каждого поста создаём задачу: загрузить комментарии и авторов
    posts.map { post ->
        async {
            // 2.1. Загружаем автора поста
            val postAuthor = getAuthor(client, post.authorId)

            // 2.2. Загружаем комментарии к посту
            val comments = getComments(client, post.id)
            println("Загружено комментариев к посту ${post.id}: ${comments.size}")

            // 2.3. Для каждого комментария загружаем автора комментария
            val commentsWithAuthors = comments.map { comment ->
                async {
                    val commentAuthor = getAuthor(client, comment.authorId)
                    CommentWithAuthor(comment, commentAuthor)
                }
            }.awaitAll()

            // 2.4. Собираем всё в объект PostWithCommentsAndAuthors
            PostWithCommentsAndAuthors(
                PostWithAuthor(post, postAuthor),
                commentsWithAuthors
            )
        }
    }.awaitAll() // Ждём завершения всех задач
}

fun main() = runBlocking {
    try {
        val startTime = System.currentTimeMillis()
        val postsWithAuthors = loadPostsWithAuthorsAndComments(client)
        val endTime = System.currentTimeMillis()

        println("\nЗагрузка завершена за ${endTime - startTime} мс")
        println("Обработано постов: ${postsWithAuthors.size}")

        postsWithAuthors.forEach { postWithComments ->
            println("\nПОСТ #${postWithComments.postWithAuthor.post.id}")
            println("Автор: ${postWithComments.postWithAuthor.author.name}")
            println("Аватар: ${postWithComments.postWithAuthor.author.avatar}")
            println("Содержание: ${postWithComments.postWithAuthor.post.content}")

            println("\nКомментарии (${postWithComments.commentsWithAuthors.size}):")
            postWithComments.commentsWithAuthors.forEach { commentWithAuthor ->
                println("  ${commentWithAuthor.comment.content} (от ${commentWithAuthor.author.name})")
            }
        }
    } catch (e: Exception) {
        println("Ошибка: ${e.message}")
        e.printStackTrace()
    }
}
