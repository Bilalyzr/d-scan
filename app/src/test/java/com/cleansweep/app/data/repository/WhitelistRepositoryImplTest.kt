package com.cleansweep.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.util.StoragePaths
import com.cleansweep.app.file
import com.cleansweep.app.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW: Long = 1_700_000_000_000L

/**
 * Rules are stored canonicalised, so the paths indexed here are real, canonical paths under a temporary folder.
 * Made-up paths such as `/r/Work` would not survive canonicalisation on every host: on Windows the JVM turns
 * them into drive-relative paths such as `C:/r/Work`.
 */
@RunWith(AndroidJUnit4::class)
class WhitelistRepositoryImplTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var base: String
    private lateinit var database: CleanSweepDatabase
    private lateinit var fileIndexRepository: FileIndexRepositoryImpl
    private lateinit var repository: WhitelistRepositoryImpl

    @Before
    fun setUp() {
        base = StoragePaths.canonicalize(temporaryFolder.root.absolutePath)
        database = inMemoryDatabase()
        fileIndexRepository = FileIndexRepositoryImpl(
            database,
            database.fileIndexDao(),
            database.suggestionItemDao(),
        )
        repository = WhitelistRepositoryImpl(
            database = database,
            whitelistRuleDao = database.whitelistRuleDao(),
            fileIndexDao = database.fileIndexDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Indexes [relativePaths] and gives every file an active suggestion, so purges can be checked through both tables. */
    private suspend fun indexWithSuggestions(vararg relativePaths: String) {
        val stored = fileIndexRepository.applyScanBatch(
            relativePaths.map { file("$base/$it") },
            scanTimestamp = 1_000L,
        )
        database.suggestionItemDao().upsertAll(
            stored.map {
                SuggestionDraft(it.id, SuggestionCategory.TEMP_FILE, 90, listOf(ReasonCode.TEMP_OR_LOG))
                    .toEntity(isDismissed = false)
            },
        )
    }

    private suspend fun activeSuggestions(): Int = database.suggestionItemDao().observeActiveCount().first()

    @Test
    fun `a folder rule removes everything indexed below it together with its suggestions`() = runTest {
        indexWithSuggestions(
            "Documents/Tax/2023.pdf",
            "Documents/Tax/2024/return.pdf",
            "Documents/other.pdf",
        )
        assertEquals(3, activeSuggestions())

        repository.addRule("$base/Documents/Tax", WhitelistRuleType.FOLDER)

        assertNull(fileIndexRepository.getByPath("$base/Documents/Tax/2023.pdf"))
        assertNull(fileIndexRepository.getByPath("$base/Documents/Tax/2024/return.pdf"))
        assertNotNull(fileIndexRepository.getByPath("$base/Documents/other.pdf"))
        assertEquals(1, activeSuggestions())
    }

    @Test
    fun `a folder rule leaves a look-alike sibling alone`() = runTest {
        indexWithSuggestions("Documents/Taxes/return.pdf", "Documents/Tax Refunds/a.pdf")

        repository.addRule("$base/Documents/Tax", WhitelistRuleType.FOLDER)

        assertNotNull(fileIndexRepository.getByPath("$base/Documents/Taxes/return.pdf"))
        assertNotNull(fileIndexRepository.getByPath("$base/Documents/Tax Refunds/a.pdf"))
        assertEquals(2, activeSuggestions())
    }

    @Test
    fun `a file rule removes exactly that file and its suggestion`() = runTest {
        indexWithSuggestions("Documents/keep.pdf", "Documents/keep.pdf.bak")

        repository.addRule("$base/Documents/keep.pdf", WhitelistRuleType.FILE)

        assertNull(fileIndexRepository.getByPath("$base/Documents/keep.pdf"))
        assertNotNull(fileIndexRepository.getByPath("$base/Documents/keep.pdf.bak"))
        assertEquals(1, activeSuggestions())
    }

    @Test
    fun `adding a path that is already whitelisted returns the existing rule and stores nothing new`() = runTest {
        val first = repository.addRule("$base/Work", WhitelistRuleType.FOLDER)

        val second = repository.addRule("$base/Work/", WhitelistRuleType.FOLDER)

        assertEquals(first, second)
        assertEquals(1, repository.observeRuleCount().first())
    }

    @Test
    fun `paths are stored in canonical form whatever shape they arrive in`() = runTest {
        val folder = temporaryFolder.newFolder("Tax")
        val messy = folder.absolutePath + File.separator + "." + File.separator
        val canonical = StoragePaths.canonicalize(folder.absolutePath)

        val rule = repository.addRule(path = messy, type = WhitelistRuleType.FOLDER)

        assertEquals(canonical, rule.path)
        assertEquals(WhitelistRuleType.FOLDER, rule.type)
        assertEquals(NOW, rule.createdAt)
        assertTrue(rule.id > 0L)
        assertEquals(listOf(rule), repository.observeRules().first())
    }

    @Test
    fun `the matcher answers for every rule in memory`() = runTest {
        repository.addRule("$base/Work", WhitelistRuleType.FOLDER)
        repository.addRule("$base/Download/keep.pdf", WhitelistRuleType.FILE)

        val matcher = repository.matcher()

        assertTrue(matcher.matches("$base/Work"))
        assertTrue(matcher.matches("$base/Work/2024/report.xlsx"))
        assertTrue(matcher.matches("$base/Download/keep.pdf"))
        assertFalse(matcher.matches("$base/Download/keep.pdf.bak"))
        assertFalse(matcher.matches("$base/Workplace/file.docx"))
    }

    @Test
    fun `isWhitelisted canonicalises the path before answering`() = runTest {
        repository.addRule("$base/Work", WhitelistRuleType.FOLDER)

        assertTrue(repository.isWhitelisted("$base/Work/sub/../report.pdf"))
        assertFalse(repository.isWhitelisted("$base/Other/report.pdf"))
    }

    @Test
    fun `removing a rule lifts the protection`() = runTest {
        val rule = repository.addRule("$base/Work", WhitelistRuleType.FOLDER)
        assertTrue(repository.isWhitelisted("$base/Work/report.pdf"))

        repository.removeRule(rule.id)

        assertFalse(repository.isWhitelisted("$base/Work/report.pdf"))
        assertEquals(0, repository.observeRuleCount().first())
    }
}
