package com.polestar.dashcamexporter

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.IOException

object ShareFiles {
    fun uri(context: Context, file: SavedMedia) = file.uri
        ?: file.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        ?: throw IOException("공유할 파일을 찾을 수 없습니다.")

    fun viewIntent(context: Context, file: SavedMedia): Intent {
        val uri = uri(context, file)
        if (file.size == 0L) throw IOException("재생할 파일을 찾을 수 없습니다.")
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun intent(context: Context, files: List<SavedMedia>): Intent {
        require(files.isNotEmpty())
        if (files.size > 50) throw IOException("한 번에 최대 50개까지 공유할 수 있습니다.")
        if (files.any { it.size == 0L || (it.uri == null && it.file?.isFile != true) }) throw IOException("공유할 파일을 찾을 수 없습니다.")
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
            putExtra(Intent.EXTRA_SUBJECT, "대시캠 파일 ${files.size}개")
            clipData = ClipData.newUri(context.contentResolver, "대시캠 파일", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
