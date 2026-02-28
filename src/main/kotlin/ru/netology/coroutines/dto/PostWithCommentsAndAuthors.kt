package ru.netology.coroutines.dto

data class PostWithCommentsAndAuthors(
    val postWithAuthor: PostWithAuthor,
    val commentsWithAuthors: List<CommentWithAuthor>
)