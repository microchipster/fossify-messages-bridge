package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Query
import org.fossify.messages.models.Attachment

@Dao
interface AttachmentsDao {
    @Query("SELECT * FROM attachments")
    fun getAll(): List<Attachment>

    @Query("DELETE FROM attachments WHERE message_id IN (:messageIds)")
    fun deleteForMessages(messageIds: List<Long>)
}
