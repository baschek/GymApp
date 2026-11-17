package de.baschek.gymapp.wear

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.wearable.Asset
import java.io.File

/** Streams large payloads through a private temporary FD instead of Binder byte arrays. */
internal inline fun <T> withTransferAsset(context: Context, bytes: ByteArray, block: (Asset) -> T): T {
    val directory = File(context.noBackupFilesDir, "wear-transfer-tmp")
    check(directory.exists() || directory.mkdirs())
    val file = File.createTempFile("payload-", ".tmp", directory)
    try {
        file.outputStream().use { it.write(bytes); it.fd.sync() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            return block(Asset.createFromFd(fd))
        }
    } finally { file.delete() }
}
