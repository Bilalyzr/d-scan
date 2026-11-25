package com.cleansweep.app.domain.scanner.whitelist

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.scanner.ScanHarness
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** The whitelist manager on real folders and files, with a scan before and after. */
@RunWith(AndroidJUnit4::class)
class WhitelistManagerTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scans: ScanHarness
    private lateinit var manager: WhitelistManager

    @Before
    fun setUp() {
        scans = ScanHarness(temporaryFolder.root)
        manager = WhitelistManager(
            whitelist = scans.whitelist,
            roots = StorageRootsProvider { listOf(scans.root) },
            metadata = NioFileMetadataReader(),
        )
    }

    @After
    fun tearDown() {
        scans.close()
    }

    /** Writes a file the scan suggests as temporary junk, scans, and returns what the index knows about it. */
    private suspend fun suggestedFile(relative: String): IndexedFile {
        scans.write(relative, "x".toByteArray())
        scans.scan()
        return requireNotNull(scans.indexed(scans.at(relative)))
    }

    private suspend fun rules() = manager.rules.first()

    @Test
    fun `protecting a folder takes everything below it out of the index and the suggestions at once`() =
        runBlocking {
            val log = suggestedFile("Documents/Tax/2024/old.log")

            val result = manager.protect(scans.at("Documents/Tax"))

            val rule = (result as ProtectionResult.Added).rule
            assertEquals(WhitelistRuleType.FOLDER, rule.type)
            assertEquals(scans.at("Documents/Tax"), rule.path)
            assertNull(scans.indexed(log.path))
            assertTrue(scans.storedSuggestions().isEmpty())
            assertTrue(manager.isProtected(scans.at("Documents/Tax/2024/old.log")))
            assertTrue(manager.isProtected(scans.at("Documents/Tax")))
            assertEquals(listOf(rule), rules())
        }

    @Test
    fun `protecting a file takes only that file out`() = runBlocking {
        val keep = suggestedFile("Download/keep.log")
        val other = suggestedFile("Download/other.log")

        val result = manager.protect(keep.path)

        val rule = (result as ProtectionResult.Added).rule
        assertEquals(WhitelistRuleType.FILE, rule.type)
        assertNull(scans.indexed(keep.path))
        assertNotNull(scans.indexed(other.path))
        assertFalse(manager.isProtected(scans.at("Download/other.log")))
        assertFalse(manager.isProtected(scans.at("Download")))
    }

    @Test
    fun `protecting a path twice changes nothing`() = runBlocking {
        scans.folder("Documents/Tax")
        val first = manager.protect(scans.at("Documents/Tax"))

        val second = manager.protect(scans.at("Documents/Tax"))

        assertEquals(ProtectionResult.AlreadyProtected((first as ProtectionResult.Added).rule), second)
        assertEquals(1, rules().size)
    }

    @Test
    fun `a path under a protected folder is reported as covered, and nothing is added for it`() = runBlocking {
        scans.folder("Documents/Tax/2024")
        manager.protect(scans.at("Documents/Tax"))

        val result = manager.protect(scans.at("Documents/Tax/2024"))

        assertEquals(ProtectionResult.AlreadyProtected(null), result)
        assertEquals(1, rules().size)
    }

    @Test
    fun `a path that is not there cannot be protected`() = runBlocking {
        assertEquals(ProtectionResult.NothingThere, manager.protect(scans.at("Documents/Missing")))
        assertEquals(ProtectionResult.NothingThere, manager.protect(""))
        assertEquals(ProtectionResult.NothingThere, manager.protect("   "))
        assertTrue(rules().isEmpty())
    }

    @Test
    fun `a storage root is never protected`() = runBlocking {
        assertEquals(ProtectionResult.WouldExcludeEverything, manager.protect(scans.root))
        assertTrue(rules().isEmpty())
    }

    @Test
    fun `a path in another shape protects the same folder`() = runBlocking {
        scans.folder("Documents/Tax")
        scans.folder("Documents/Other")

        val added = manager.protect(scans.at("Documents/Tax/")) as ProtectionResult.Added
        val throughParent = manager.protect(scans.at("Documents/Other") + "/../Tax")

        assertEquals(scans.at("Documents/Tax"), added.rule.path)
        assertEquals(ProtectionResult.AlreadyProtected(added.rule), throughParent)
        assertEquals(1, rules().size)
    }

    @Test
    fun `unprotecting lets the next scan suggest the files again`() = runBlocking {
        val log = suggestedFile("Documents/Tax/old.log")
        val rule = (manager.protect(scans.at("Documents/Tax")) as ProtectionResult.Added).rule

        manager.unprotect(rule)

        assertFalse(manager.isProtected(log.path))
        assertTrue(rules().isEmpty())

        scans.scan()

        assertNotNull(scans.indexed(log.path))
        assertEquals(listOf(log.path), scans.storedSuggestions().map { it.file.path })
    }
}
