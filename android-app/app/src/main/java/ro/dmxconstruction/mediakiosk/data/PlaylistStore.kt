package ro.dmxconstruction.mediakiosk.data

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

class PlaylistStore(private val directory: File, private val gson: Gson = Gson()) {
    private val target = File(directory, "playlist.json")

    fun save(snapshot: PlaylistSnapshot) {
        directory.mkdirs()
        val temporary = File(directory, "playlist.json.part")
        FileOutputStream(temporary).use { output ->
            output.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Playlistul nu a putut fi salvat atomic.")
        }
    }

    fun load(): PlaylistSnapshot? = runCatching {
        if (!target.isFile) null else target.reader(Charsets.UTF_8).use { gson.fromJson(it, PlaylistSnapshot::class.java) }
    }.getOrNull()

    fun clear() {
        target.delete()
        removePartials()
    }

    fun removePartials() { File(directory, "playlist.json.part").delete() }
}
