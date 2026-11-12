package dev.pocketmodel.studio.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 用户在导入横幅上点了「取消」 */
class ImportCancelledException : Exception("已取消导入")

enum class ImportPhase { PREPARING, EXTRACTING, INSTALLING, VERIFYING }

/**
 * 导入进度。bytesTotal 为 -1 表示拿不到源文件大小（少数 SAF provider 不报 SIZE），
 * 此时 UI 用不确定进度条。
 */
data class ImportProgress(
    val phase: ImportPhase,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = -1L,
    val filesDone: Int = 0,
    val detail: String = "",
) {
    val fraction: Float?
        get() = if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat() else null
}

/** 只用来数「从 SAF 读了多少字节」，与压缩比无关，所以进度条和文件大小能对得上 */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) count += s
        return s
    }
}

data class InstalledModel(
    val manifest: ModelManifest,
    val dir: File,
    val bytes: Long,
    /** 内置运行时（随包时不参与，可能为 null） */
    val runtime: RuntimeSpec?,
    /** 是否自带运行框架 */
    val embeddedRuntime: Boolean = false,
    val issues: List<String> = emptyList(),
) {
    val kind: ModelKind get() = manifest.kindEnum
    val ready: Boolean get() = issues.isEmpty()

    /** 运行时来源的一句话描述，给卡片副标题用 */
    val runtimeLabel: String
        get() = if (embeddedRuntime) "随包运行框架" else runtime?.displayName ?: "未指定"
}

/**
 * 模型库：filesDir/models/<id>/manifest.json + weights/。
 *
 * 为什么不放公共存储：scoped storage 下 app 无法在外部目录 exec 或建符号链接，
 * 而且 QNN 的 context binary 必须能被子进程按绝对路径打开。放进 app 私有目录最省事，
 * 导出用 SAF 的 ACTION_CREATE_DOCUMENT。
 */
class ModelRepository(private val ctx: Context) {

    val root: File = File(ctx.filesDir, "models").apply { mkdirs() }

    suspend fun list(): List<InstalledModel> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { parse(it) }?.sortedBy { it.manifest.name } ?: emptyList()
    }

    fun parse(dir: File): InstalledModel? {
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return null
        val manifest = runCatching { ManifestJson.decodeFromString<ModelManifest>(mf.readText()) }.getOrNull() ?: return null
        val runtime = Runtimes.byId(manifest.runtime)
        val embedded = RuntimeResolver.hasEmbedded(dir)
        val issues = buildList {
            // 运行时可用性：随包优先，其次内置
            runCatching { RuntimeResolver.resolve(ctx, manifest, dir, runtime) }
                .onFailure { add(it.message ?: "运行时不可用") }
            manifest.weights.forEach { w ->
                val f = File(dir, w.path)
                if (!f.exists() && !w.optional) add("缺少权重 ${w.path}")
                if (w.bytes > 0 && f.exists() && f.length() != w.bytes) add("权重大小不符 ${w.path}")
            }
        }
        return InstalledModel(manifest, dir, dirSize(dir), runtime, embedded, issues)
    }

    /**
     * 从 SAF 的 zip/.mlm 导入。返回导入结果或抛出可读错误。
     *
     * 7 GB 的包要写两份（解压到 cache、再搬进 filesDir），所以中间必须能报进度，
     * 否则界面就是「什么都没发生，只有占用空间在长」。
     * onProgress 在 IO 线程回调，UI 侧直接写 Compose state 即可（快照写入线程安全）。
     */
    suspend fun importZip(
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): InstalledModel = withContext(Dispatchers.IO) {
        cleanupStaleStages()
        val total = querySize(uri)
        var lastEmit = 0L
        fun emit(p: ImportProgress, force: Boolean = false) {
            val now = System.currentTimeMillis()
            // 1 MB 一个 entry 的包会有上万次回调，节流到 ~8 fps，避免无谓重组
            if (force || now - lastEmit >= 120) {
                lastEmit = now
                onProgress(if (p.bytesTotal < 0L && total > 0L) p.copy(bytesTotal = total) else p)
            }
        }

        val stage = File(ctx.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        var installed: File? = null
        try {
            emit(ImportProgress(ImportPhase.EXTRACTING, 0L, total, 0, "解压中"), force = true)

            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                val counting = CountingInputStream(raw)
                ZipInputStream(counting.buffered(1 shl 20)).use { zip ->
                    var entry = zip.nextEntry
                    var files = 0
                    while (entry != null) {
                        if (isCancelled()) throw ImportCancelledException()
                        val name = entry.name
                        val target = File(stage, name).canonicalFile
                        if (!target.path.startsWith(stage.canonicalPath + File.separator)) {
                            throw SecurityException("压缩包路径非法：$name")
                        }
                        // 大权重在打包时被切成 <path>.partNN：Android 的 ZipInputStream 不支持
                        // zip64，单个 entry 超过 4 GiB 就解不出来（会静默截断）。
                        // 这里按写入顺序把小片追加回同一个文件，manifest 里仍然只有一个 path。
                        val part = PART_RE.find(name)
                        val outFile = if (part != null) {
                            File(stage, part.groupValues[1]).canonicalFile.also {
                                if (!it.path.startsWith(stage.canonicalPath + File.separator)) {
                                    throw SecurityException("压缩包路径非法：$name")
                                }
                            }
                        } else target
                        val append = part != null && part.groupValues[2].toInt() > 0
                        if (entry.isDirectory) target.mkdirs() else {
                            outFile.parentFile?.mkdirs()
                            java.io.FileOutputStream(outFile, append).buffered(1 shl 20).use { out ->
                                // 必须边读边报：模型包里的权重是单个几 GB 的 entry，
                                // 若像以前那样 copyTo 完再报一次，整个拷贝期间进度会一直是 0%，
                                // 结束瞬间跳到 100%——等于没有进度条。
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    if (isCancelled()) throw ImportCancelledException()
                                    val n = zip.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    emit(ImportProgress(ImportPhase.EXTRACTING, counting.count, total, files))
                                }
                            }
                            files++
                            emit(ImportProgress(ImportPhase.EXTRACTING, counting.count, total, files), force = true)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("无法读取所选文件")

            if (isCancelled()) throw ImportCancelledException()

            // manifest 可能在根，也可能在单层子目录
            val mf = File(stage, "manifest.json").takeIf { it.exists() }
                ?: stage.listFiles()?.firstOrNull { File(it, "manifest.json").exists() }?.let { File(it, "manifest.json") }
                ?: throw IllegalStateException("压缩包内没有 manifest.json")
            val pkgRoot = mf.parentFile!!

            val manifest = ManifestJson.decodeFromString<ModelManifest>(mf.readText())
            val dest = File(root, sanitize(manifest.id))
            if (dest.exists()) {
                // 不静默覆盖：交给 UI 提示「已存在」
                throw IllegalStateException("模型 ${manifest.id} 已存在，请先删除")
            }

            // cache 与 files 在同一分区，正常情况下 rename 是纯元数据操作。
            // 以前无条件 copyRecursively，等于把 7 GB 又写了一遍，白等一两分钟。
            emit(ImportProgress(ImportPhase.INSTALLING, total, total, 0, "安装到模型库"), force = true)
            val moved = runCatching { pkgRoot.renameTo(dest) }.getOrDefault(false)
            if (!moved) {
                pkgRoot.copyRecursively(dest, overwrite = true)
            }
            installed = dest

            emit(ImportProgress(ImportPhase.VERIFYING, total, total, 0, "校验中"), force = true)
            parse(dest) ?: throw IllegalStateException("导入后解析失败")
        } catch (e: Throwable) {
            // 安装阶段的半成品要清掉，否则下次导入会撞「模型已存在」
            installed?.let { if (it.exists()) it.deleteRecursively() }
            throw e
        } finally {
            stage.deleteRecursively()
        }
    }

    /** 上次进程被杀留下的 import_* 目录，不清会白占几 GB cache */
    private fun cleanupStaleStages() {
        runCatching {
            ctx.cacheDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("import_") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    private fun querySize(uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use -1L
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && !c.isNull(i)) c.getLong(i) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    suspend fun delete(model: InstalledModel): Unit = withContext(Dispatchers.IO) {
        model.dir.deleteRecursively()
    }

    fun storageUsed(): Long = dirSize(root)

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sanitize(id: String): String = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        /**
         * `weights/x.gguf.part00` → 组 1 = `weights/x.gguf`，组 2 = 片号。
         * 约定来自 `tools/pack_model.py --part-size`：单个 entry 必须 < 4 GiB，
         * 否则 zip64 让 ZipInputStream 读不出来。
         */
        private val PART_RE = Regex("""^(.*)\.part(\d{2,})$""")
    }
}
