package com.cleansweep.app

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Guards the permission and backup posture of the manifest that actually ships (PRD sections 7 and 8).
 *
 * The tests read the packaged debug manifest: the last XML form of the manifest before it is compiled into the
 * APK. AGP's `processDebugManifestForPackage` produces it, and it is part of the unit-test task graph, so it is
 * always present when these tests run. A permission that a dependency sneaks in, or the removal of the
 * `tools:node="remove"` line that strips INTERNET, fails here rather than in Play review.
 */
@RunWith(AndroidJUnit4::class)
class ManifestPrivacyTest {

    private data class PackagedManifest(
        /** Permission name to its maxSdkVersion, if any. */
        val permissions: Map<String, String?>,
        val applicationAttributes: Map<String, String?>,
    )

    /** Every storage domain a backup rule can name, each with the path of its root. */
    private val everyDomainRoot: Set<Pair<String, String>> = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    ).map { it to "." }.toSet()

    // AGP runs unit tests with the module directory as the working directory.
    private val moduleDir: File = File(System.getProperty("user.dir"))

    private fun packagedManifest(): File {
        val candidates = File(moduleDir, "build/intermediates/packaged_manifests")
            .walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filter { it.parentFile?.name == "processDebugManifestForPackage" }
            .toList()
        return candidates.singleOrNull()
            ?: error("Expected exactly one packaged debug manifest, found: $candidates")
    }

    private fun attribute(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)

    private fun readPackagedManifest(): PackagedManifest {
        val parser = Xml.newPullParser()
        val permissions = mutableMapOf<String, String?>()
        val applicationAttributes = mutableMapOf<String, String?>()

        // The parser reads lazily, so the stream has to stay open until parsing is done.
        packagedManifest().inputStream().use { stream ->
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "uses-permission" -> {
                            val permission = attribute(parser, "name")
                            if (permission != null) {
                                permissions[permission] = attribute(parser, "maxSdkVersion")
                            }
                        }
                        "application" -> for (index in 0 until parser.attributeCount) {
                            applicationAttributes[parser.getAttributeName(index)] = parser.getAttributeValue(index)
                        }
                    }
                }
                event = parser.next()
            }
        }
        return PackagedManifest(permissions, applicationAttributes)
    }

    /**
     * Reads a backup rules file and maps each element that holds `<exclude>` entries to the `domain`/`path` pairs
     * it excludes. These files carry no Android namespace, so the attributes are read without one.
     */
    private fun readExclusions(rules: File): Map<String, Set<Pair<String?, String?>>> {
        val parser = Xml.newPullParser()
        val excluded = mutableMapOf<String, MutableSet<Pair<String?, String?>>>()
        val containers = ArrayDeque<String>()

        rules.inputStream().use { stream ->
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "exclude" -> {
                            val section = containers.lastOrNull() ?: error("<exclude> outside a section")
                            excluded.getOrPut(section) { mutableSetOf() }
                                .add(parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path"))
                        }
                        "include" -> error("${rules.name} must not include anything")
                        else -> containers.addLast(parser.name)
                    }
                    XmlPullParser.END_TAG -> if (parser.name != "exclude") containers.removeLastOrNull()
                }
                event = parser.next()
            }
        }
        return excluded
    }

    // ---- Permissions ----

    @Test
    fun `the app can never open a network socket`() {
        val permissions = readPackagedManifest().permissions.keys

        assertFalse(
            "INTERNET must never be requested. Requested: $permissions",
            permissions.any { it.contains("INTERNET") },
        )
    }

    @Test
    fun `only storage and WorkManager's own permissions are requested`() {
        // An allowlist rather than a denylist: a new permission only gets in through this list, on purpose.
        val expected = setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            // Merged in by WorkManager. All are normal permissions and none of them grants network access.
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
            // Defined by the app itself (androidx.core) for the receiver-exported flag on Android 13.
            "com.cleansweep.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )

        assertEquals(expected, readPackagedManifest().permissions.keys)
    }

    @Test
    fun `All Files Access is requested without an SDK cap`() {
        val permissions = readPackagedManifest().permissions

        assertTrue("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertNull(permissions["android.permission.MANAGE_EXTERNAL_STORAGE"])
    }

    @Test
    fun `legacy storage permissions stop at Android 10`() {
        val permissions = readPackagedManifest().permissions

        assertEquals("29", permissions["android.permission.READ_EXTERNAL_STORAGE"])
        assertEquals("29", permissions["android.permission.WRITE_EXTERNAL_STORAGE"])
    }

    @Test
    fun `Android 10 is opted into the legacy storage model`() {
        assertEquals("true", readPackagedManifest().applicationAttributes["requestLegacyExternalStorage"])
    }

    // ---- Backup ----

    @Test
    fun `the database describing the user's files is never backed up`() {
        val attributes = readPackagedManifest().applicationAttributes

        assertEquals("false", attributes["allowBackup"])
        assertEquals("@xml/data_extraction_rules", attributes["dataExtractionRules"])
        assertEquals("@xml/backup_rules", attributes["fullBackupContent"])
    }

    @Test
    fun `from Android 12 on, cloud backup and device transfer exclude every storage domain`() {
        // allowBackup alone is not enough there: a transfer mode without rules is fully enabled.
        val excluded = readExclusions(File(moduleDir, "src/main/res/xml/data_extraction_rules.xml"))

        assertEquals(setOf("cloud-backup", "device-transfer"), excluded.keys)
        excluded.forEach { (section, rules) ->
            assertEquals("exclusions in <$section>", everyDomainRoot, rules)
        }
    }

    @Test
    fun `on Android 11 and lower, file-based backup excludes every storage domain`() {
        val excluded = readExclusions(File(moduleDir, "src/main/res/xml/backup_rules.xml"))

        assertEquals(setOf("full-backup-content"), excluded.keys)
        assertEquals(everyDomainRoot, excluded.getValue("full-backup-content"))
    }
}
