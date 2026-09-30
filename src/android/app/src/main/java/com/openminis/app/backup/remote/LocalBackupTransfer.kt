package com.openminis.app.backup.remote
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import java.io.File
class LocalBackupTransfer(private val context: Context) {
    data class Progress(val bytesSent: Long, val totalBytes: Long, val bytesPerSecond: Double = 0.0) {
        val fraction get() = if (totalBytes > 0) bytesSent.toDouble() / totalBytes else 0.0
        val secondsRemaining: Long? get() = if (bytesPerSecond > 0) ((totalBytes - bytesSent) / bytesPerSecond).toLong() else null
    }
    class CancelFlag { @Volatile private var cancelled = false; fun cancel() { cancelled = true }; fun isCancelled() = cancelled }
    class CancelledException: Exception("Transfer cancelled")
    data class RemotePackage(val key: String, val displayName: String, val size: Long, val modified: Long?, val partCount: Int) { val isChunked get() = partCount > 1 }
    data class RemoteEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long?)
    private fun tree(remote: LocalDestinationStore.Remote) = Uri.parse(remote.params.getValue(LocalDestinationStore.PARAM_TREE_URI))
    private fun doc(remote: LocalDestinationStore.Remote, path: String): Uri {
        val t = tree(remote)
        return if (path.startsWith("content://")) Uri.parse(path) else DC.buildDocumentUriUsingTree(t, DC.getTreeDocumentId(t))
    }
    fun upload(packageFile: File, remote: LocalDestinationStore.Remote, backupId: String = "", isCancelled: () -> Boolean = { false }, onProgress: ((Progress) -> Unit)? = null) {
        LocalFolderDelivery(context).deliver(packageFile, tree(remote).toString(), isCancelled) { sent, total -> onProgress?.invoke(Progress(sent, total)) }
    }
    fun deletePackage(remote: LocalDestinationStore.Remote, packageName: String) { LocalFolderDelivery(context).delete(tree(remote).toString(), packageName) }
    fun deletePackage(remote: LocalDestinationStore.Remote, pkg: RemotePackage) {
        check(DC.deleteDocument(context.contentResolver, doc(remote, pkg.key))) { "Delete failed" }
    }
    fun listDirectory(remote: LocalDestinationStore.Remote, path: String): List<RemoteEntry> {
        val parent = doc(remote, path)
        val children = DC.buildChildDocumentsUriUsingTree(tree(remote), DC.getDocumentId(parent))
        val entries = mutableListOf<RemoteEntry>()
        val columns = arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME, DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED)
        val cursor = context.contentResolver.query(children, columns, null, null, null) ?: error("Folder unavailable")
        cursor.use { c -> while(c.moveToNext()) {
            val name = c.getString(1)
            val directory = c.getString(2) == DC.Document.MIME_TYPE_DIR
            if (directory || (name.endsWith(".zip") || name.endsWith(".minisbak"))) entries += RemoteEntry(DC.buildDocumentUriUsingTree(tree(remote), c.getString(0)).toString(), name, directory, c.getLong(3), c.getLong(4))
        } }
        return entries
    }
    fun listPackages(remote: LocalDestinationStore.Remote) = listDirectory(remote, "").filterNot { it.isDirectory }.map { RemotePackage(it.path, it.name, it.size, it.modified, 1) }
    fun download(pkg: RemotePackage, remote: LocalDestinationStore.Remote, destination: File, cancel: CancelFlag? = null, onProgress: ((Progress) -> Unit)? = null) {
        try {
            val input = context.contentResolver.openInputStream(doc(remote, pkg.key)) ?: error("File unavailable")
            input.use { source -> destination.outputStream().use { out ->
                val buf = ByteArray(8192); var sent = 0L
                while(true) { if (cancel?.isCancelled() == true) throw CancelledException(); val n = source.read(buf); if(n < 0) break; out.write(buf, 0, n); sent += n; onProgress?.invoke(Progress(sent, pkg.size)) }
            } }
            check(destination.length() == pkg.size) { "Incomplete backup" }
        } catch(e: Exception) { destination.delete(); throw e }
    }
}
