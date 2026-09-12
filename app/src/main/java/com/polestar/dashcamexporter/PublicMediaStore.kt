package com.polestar.dashcamexporter

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException

/** Publishes completed DVR exports into a normal user-visible Android media folder. */
object PublicMediaStore {
    fun publish(context: Context, item: SavedMedia, stop: StopToken,
                progress: (Long, Long) -> Unit): Boolean {
        val resolver = context.contentResolver
        val (collection, relativePath) = if (item.kind == MediaKind.PHOTO) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Polestar Dashcam/${item.kind.api}/"
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Polestar Dashcam/${item.kind.api}/"
        }
        if (findExisting(resolver, collection, relativePath, item.name) != null) return false

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("공용 미디어 폴더에 파일을 만들 수 없습니다.")
        var complete = false
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("공용 미디어 파일을 열 수 없습니다.")
            output.use { sink -> (item.file ?: throw IOException("공용 폴더에 저장할 원본 파일을 찾을 수 없습니다.")).inputStream().use { input ->
                StreamCopy.copy(input, sink, item.size, stop, progress)
                sink.flush()
            } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            complete = true
            return true
        } finally {
            if (!complete) resolver.delete(uri, null, null)
        }
    }

    private fun findExisting(resolver: ContentResolver, collection: android.net.Uri,
                             relativePath: String, name: String): android.net.Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(collection, projection, selection, arrayOf(name, relativePath), null)?.use { cursor ->
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }
}
