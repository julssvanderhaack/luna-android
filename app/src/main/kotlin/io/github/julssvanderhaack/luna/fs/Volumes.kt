package io.github.julssvanderhaack.luna.fs

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class VolumeType { INTERNAL, USB, SD }

data class Volume(
    val label: String,
    val path: String,
    val type: VolumeType,
    val total: Long,
    val free: Long,
    val readOnly: Boolean,
)

/**
 * Internal storage plus whatever is plugged in. StorageManager calls back when
 * a pendrive or disk is mounted or pulled out, so the home screen updates live.
 */
class Volumes(private val context: Context) {
    private val sm = context.getSystemService(StorageManager::class.java)
    private val _list = MutableStateFlow(read())
    val list: StateFlow<List<Volume>> = _list

    private val callback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) = refresh()
    }

    init {
        sm.registerStorageVolumeCallback(context.mainExecutor, callback)
    }

    fun refresh() {
        _list.value = read()
    }

    /** The volume [path] lives on, for titles and free space. */
    fun of(path: String): Volume? = _list.value.filter { Paths.isInside(path, it.path) }.maxByOrNull { it.path.length }

    private fun read(): List<Volume> = sm.storageVolumes.mapNotNull { v ->
        val state = v.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
        val dir = v.directory ?: return@mapNotNull null
        val desc = v.getDescription(context).orEmpty()
        val type = when {
            v.isPrimary && !v.isRemovable -> VolumeType.INTERNAL
            // there's no public "is USB" flag; the system's own description says so
            desc.contains("usb", ignoreCase = true) || isUsbDisk(v) -> VolumeType.USB
            else -> VolumeType.SD
        }
        val stat = runCatching { StatFs(dir.path) }.getOrNull()
        Volume(
            label = when (type) {
                VolumeType.INTERNAL -> "Almacenamiento interno"
                else -> desc.ifBlank { if (type == VolumeType.USB) "Unidad USB" else "Tarjeta SD" }
            },
            path = dir.path,
            type = type,
            total = stat?.totalBytes ?: 0,
            free = stat?.availableBytes ?: 0,
            readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }.sortedBy { it.type.ordinal }

    /** USB disks show up as /mnt/media_rw/<uuid> backed by a "public:8,xx" block device, SD cards by "public:179,xx". */
    private fun isUsbDisk(v: StorageVolume): Boolean {
        val uuid = v.uuid ?: return false
        return runCatching {
            File("/proc/mounts").readLines().any { line -> line.contains(uuid) && line.startsWith("/dev/block/vold/public:8,") }
        }.getOrDefault(false)
    }
}
