package com.hadi.smsbot

/**
 * Local display-only recipient grouping; never calls a messaging service.
 *
 * This is a user-interface grouping limit, NOT an official platform rate limit.
 * Explicitly capped at 256 to keep the alpha UI manageable.
 */
object BatchPlanner {
    const val MAX_LOCAL_GROUP_SIZE = 256

    fun groupIndices(totalRecipients: Int, requestedSize: Int): List<List<Int>> {
        if (totalRecipients <= 0) return emptyList()
        val size = requestedSize.coerceIn(1, MAX_LOCAL_GROUP_SIZE)
        return (0 until totalRecipients).chunked(size)
    }
}
