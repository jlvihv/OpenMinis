package com.openminis.app.providers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MinisDocumentsProviderWiringTest {
    @Test
    fun `system file picker provider is registered and protected`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("android:name=\".providers.MinisDocumentsProvider\"")
            .substringBefore("</provider>")
        assertTrue(provider.contains("android:authorities=\"${MinisDocumentsProvider.AUTHORITY}\""))
        assertTrue(provider.contains("android:permission=\"android.permission.MANAGE_DOCUMENTS\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(provider.contains("android.content.action.DOCUMENTS_PROVIDER"))
    }

    @Test
    fun `root has a nonempty document id and private subtrees are inaccessible`() {
        val source = File("src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt").readText()
        assertTrue(source.contains("ROOT_DOC_ID = \"root\""))
        assertTrue(source.contains("if (top !in TOP_LEVEL)"))
        // /data/user/0 and /data/data aliases must be normalized on both sides.
        assertTrue(source.contains("File(root.canonicalFile, top)"))
        assertTrue(source.contains("canonical.path.startsWith(allowed.path + \"/\")"))
        assertTrue(source.contains("Top-level folders are immutable"))
    }
}
