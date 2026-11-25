package com.cleansweep.app.domain.scanner.junk

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.file
import com.cleansweep.app.folder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private const val DAY: Long = 24L * 60 * 60 * 1000
private const val NOW: Long = 1_800_000_000_000L

private class FakeInstalledApps(
    private val supersededPaths: Set<String> = emptySet(),
) : InstalledApkOracle {
    val askedAbout = CopyOnWriteArrayList<String>()

    override suspend fun isSupersededByInstalledApp(apkPath: String): Boolean {
        askedAbout += apkPath
        return apkPath in supersededPaths
    }
}

class JunkRulesTest {

    @Test
    fun `age is whole days and never negative`() {
        assertEquals(0L, JunkRules.ageInDays(NOW, NOW))
        assertEquals(0L, JunkRules.ageInDays(NOW - DAY + 1, NOW))
        assertEquals(1L, JunkRules.ageInDays(NOW - DAY, NOW))
        assertEquals(90L, JunkRules.ageInDays(NOW - 90 * DAY, NOW))
        assertEquals(0L, JunkRules.ageInDays(NOW + 5 * DAY, NOW)) // a clock that ran ahead on the device
    }

    @Test
    fun `recent and old are the two sides of the seven-day line`() {
        assertTrue(JunkRules.isRecent(NOW - 6 * DAY, NOW))
        assertTrue(JunkRules.isRecent(NOW, NOW))
        assertFalse(JunkRules.isRecent(NOW - 7 * DAY, NOW))
        assertTrue(JunkRules.ageInDays(NOW - 7 * DAY, NOW) >= JunkRules.OLD_INSTALLER_DAYS)
    }

    @Test
    fun `untouched starts at exactly ninety days`() {
        assertFalse(JunkRules.isStale(NOW - 90 * DAY + 1, NOW))
        assertTrue(JunkRules.isStale(NOW - 90 * DAY, NOW))
    }
}

class JunkDetectorsTest {

    private fun context(settings: UserPreferences = UserPreferences()) = DetectionContext(NOW, settings)

    private fun reasons(findings: List<Finding>): Map<String, List<ReasonCode>> =
        findings.associate { it.file.path to it.reasons }

    // ---- Temporary files ----

    @Test
    fun `tmp, log and bak files and thumbnail caches are temporary files`() {
        val files = listOf(
            file("/r/notes.log"), file("/r/backup.bak"), file("/r/cache.tmp"), file("/r/DCIM/.thumbnails/thumb1"),
            file("/r/notes.txt"), file("/r/app.apk"),
        )

        val findings = TempFileDetector().detect(files, context())

        assertEquals(setOf("/r/notes.log", "/r/backup.bak", "/r/cache.tmp", "/r/DCIM/.thumbnails/thumb1"), reasons(findings).keys)
        assertTrue(findings.all { it.category == SuggestionCategory.TEMP_FILE })
        assertEquals(listOf(ReasonCode.TEMP_OR_LOG), reasons(findings).getValue("/r/notes.log"))
        assertEquals(listOf(ReasonCode.THUMBNAIL_CACHE), reasons(findings).getValue("/r/DCIM/.thumbnails/thumb1"))
    }

    @Test
    fun `a temporary file modified in the last days carries the recent signal`() {
        val recent = file("/r/fresh.tmp", lastModified = NOW - 2 * DAY)
        val old = file("/r/old.tmp", lastModified = NOW - 30 * DAY)

        val findings = reasons(TempFileDetector().detect(listOf(recent, old), context()))

        assertEquals(listOf(ReasonCode.TEMP_OR_LOG, ReasonCode.RECENT), findings.getValue("/r/fresh.tmp"))
        assertEquals(listOf(ReasonCode.TEMP_OR_LOG), findings.getValue("/r/old.tmp"))
    }

    // ---- Zero-byte files ----

    @Test
    fun `zero-byte files are reported and an empty folder is not a zero-byte file`() {
        val files = listOf(file("/r/empty.dat", sizeBytes = 0), file("/r/full.dat", sizeBytes = 1), folder("/r/Empty"))

        val findings = ZeroByteDetector().detect(files, context())

        assertEquals(listOf("/r/empty.dat"), findings.map { it.file.path })
        assertEquals(SuggestionCategory.ZERO_BYTE, findings.single().category)
        assertEquals(listOf(ReasonCode.ZERO_BYTE), findings.single().reasons)
    }

    // ---- Large forgotten files ----

    @Test
    fun `a file is large and forgotten when it is over the threshold and over the stale window`() {
        val settings = UserPreferences(largeFileThresholdBytes = 50L * 1024 * 1024, largeFileStaleDays = 90)
        val files = listOf(
            file("/r/old-big.bin", sizeBytes = 51L * 1024 * 1024, lastModified = NOW - 120 * DAY),
            file("/r/new-big.bin", sizeBytes = 51L * 1024 * 1024, lastModified = NOW - 10 * DAY),
            file("/r/old-small.bin", sizeBytes = 49L * 1024 * 1024, lastModified = NOW - 120 * DAY),
            file("/r/old-exact.bin", sizeBytes = 50L * 1024 * 1024, lastModified = NOW - 90 * DAY),
        )

        val findings = LargeForgottenFileDetector().detect(files, context(settings))

        assertEquals(listOf("/r/old-big.bin", "/r/old-exact.bin"), findings.map { it.file.path })
        assertTrue(findings.all { it.category == SuggestionCategory.LARGE_FORGOTTEN })
        assertTrue(findings.all { it.reasons == listOf(ReasonCode.LARGE_FILE) })
    }

    @Test
    fun `the large-file threshold and stale window come from the settings`() {
        val settings = UserPreferences(largeFileThresholdBytes = 1_000L, largeFileStaleDays = 30)
        val files = listOf(file("/r/a.bin", sizeBytes = 2_000L, lastModified = NOW - 31 * DAY))

        assertEquals(1, LargeForgottenFileDetector().detect(files, context(settings)).size)
        assertTrue(LargeForgottenFileDetector().detect(files, context()).isEmpty())
    }

    // ---- Obsolete installers ----

    @Test
    fun `an installer whose app is installed at that version or newer is reported however new it is`() = runBlocking {
        val installed = FakeInstalledApps(supersededPaths = setOf("/r/Download/app.apk"))
        val files = listOf(file("/r/Download/app.apk", lastModified = NOW), file("/r/Download/other.apk", lastModified = NOW))

        val findings = ObsoleteApkDetector(installed).detect(files, context())

        assertEquals(listOf("/r/Download/app.apk"), findings.map { it.file.path })
        assertEquals(SuggestionCategory.OBSOLETE_INSTALLER, findings.single().category)
        assertEquals(listOf(ReasonCode.INSTALLED_APK), findings.single().reasons)
    }

    @Test
    fun `an installer that is not installed is only reported once it is older than a week`() = runBlocking {
        val files = listOf(
            file("/r/Download/week-old.apk", lastModified = NOW - 7 * DAY),
            file("/r/Download/fresh.apk", lastModified = NOW - 6 * DAY),
        )

        val findings = ObsoleteApkDetector(InstalledApkOracle.NONE).detect(files, context())

        assertEquals(listOf("/r/Download/week-old.apk"), findings.map { it.file.path })
        assertEquals(listOf(ReasonCode.OLD_INSTALLER), findings.single().reasons)
    }

    @Test
    fun `only plain APK files are inspected, and XAPK files go by age alone`() = runBlocking {
        val installed = FakeInstalledApps(supersededPaths = setOf("/r/a.apk"))
        val files = listOf(
            file("/r/a.apk", lastModified = NOW),
            file("/r/game.xapk", lastModified = NOW - 30 * DAY),
            file("/r/new.xapk", lastModified = NOW - DAY),
            file("/r/notes.log"),
        )

        val findings = ObsoleteApkDetector(installed).detect(files, context())

        assertEquals(listOf("/r/a.apk", "/r/game.xapk"), findings.map { it.file.path })
        assertEquals(listOf(ReasonCode.OLD_INSTALLER), findings.last().reasons)
        assertEquals(listOf("/r/a.apk"), installed.askedAbout.toList())
    }

    @Test
    fun `the age rules ignore entries modified in the future because of a device clock`() = runBlocking {
        val files = listOf(file("/r/future.apk", lastModified = NOW + 30 * DAY))

        assertTrue(ObsoleteApkDetector(InstalledApkOracle.NONE).detect(files, context()).isEmpty())
    }
}
