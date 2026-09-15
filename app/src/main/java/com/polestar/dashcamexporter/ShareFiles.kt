package com.polestar.dashcamexporter

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.IOException

object ShareFiles {
    fun uri(context: Context, file: SavedMedia) = file.uri
        ?: file.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        ?: throw IOException("Unable to find files to share.")

    fun viewIntent(context: Context, file: SavedMedia): Intent {
        val uri = uri(context, file)
        if (file.size == 0L) throw IOException("Unable to find file to play.")
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun intent(context: Context, files: List<SavedMedia>): Intent {
        require(files.isNotEmpty())
        if (files.size > 50) throw IOException("You can share up to 50 files at once.")
        if (files.any { it.size == 0L || (it.uri == null && it.file?.isFile != true) }) throw IOException("Unable to find files to share.")
        val uris = ArrayList(files.map { uri(context, it) })
        val types = files.map { it.mime }.distinct()
        val mime = if (types.size == 1) types.first() else {
            val families = types.map { it.substringBefore('/') }.distinct()
            if (families.size == 1) "${families.first()}/*" else "*/*"
        }
        return Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Dashcam files (${files.size})")
            clipData = ClipData.newUri(context.contentResolver, "Dashcam files", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
