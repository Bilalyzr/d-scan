package com.cleansweep.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test on real device SQLite. Robolectric covers the query logic on the JVM; this confirms the schema opens
 * and the foreign-key cascade is enforced by the platform database on an actual device, including Android 10's
 * older SQLite. Needs a device or emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CleanSweepDatabaseInstrumentedTest {

    @Test
    fun schemaOpensAndSuggestionsFollowTheirFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, CleanSweepDatabase::class.java).build()
        try {
            val fileIndexDao = database.fileIndexDao()
            val suggestionDao = database.suggestionItemDao()

            val fileId = fileIndexDao.upsertAll(listOf(indexedFile("/storage/emulated/0/Download/notes.pdf").toEntity())).single()
            suggestionDao.upsertAll(listOf(suggestion(fileId)))

            assertEquals(1, suggestionDao.observeActiveCount().first())

            fileIndexDao.deleteByPath("/storage/emulated/0/Download/notes.pdf")

            assertNull(fileIndexDao.getByPath("/storage/emulated/0/Download/notes.pdf"))
            assertEquals(0, suggestionDao.observeActiveCount().first())
        } finally {
            database.close()
        }
    }

    private fun indexedFile(path: String) = IndexedFile(
        id = 0L,
        path = path,
        name = path.substringAfterLast('/'),
        extension = "pdf",
        mimeType = "application/pdf",
        category = FileCategory.DOCUMENT,
        sizeBytes = 1_024L,
        lastModified = 1_700_000_000_000L,
        createdAt = null,
        scanTimestamp = 1_700_000_000_000L,
    )

    private fun suggestion(fileId: Long): SuggestionItemEntity =
        SuggestionDraft(fileId, SuggestionCategory.EXACT_DUPLICATE, 85, listOf(ReasonCode.EXACT_COPY))
            .toEntity(isDismissed = false)
}
