package ro.dmxconstruction.mediakiosk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.dmxconstruction.mediakiosk.snapshot
import java.io.File

class PlaylistStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `playlistul persista si inlocuieste atomic versiunea veche`() {
        val store = PlaylistStore(temporary.root)
        store.save(snapshot(1))
        assertEquals(1, store.load()?.playlist?.version)
        store.save(snapshot(2))
        assertEquals(2, store.load()?.playlist?.version)
        assertFalse(File(temporary.root, "playlist.json.part").exists())
    }

    @Test fun `un fisier partial nu inlocuieste playlistul valid`() {
        val store = PlaylistStore(temporary.root)
        store.save(snapshot(4))
        File(temporary.root, "playlist.json.part").writeText("{corupt")
        assertEquals(4, store.load()?.playlist?.version)
        store.removePartials()
        assertFalse(File(temporary.root, "playlist.json.part").exists())
    }

    @Test fun `playlistul corupt are fallback null si poate fi curatat`() {
        File(temporary.root, "playlist.json").writeText("invalid")
        val store = PlaylistStore(temporary.root)
        assertNull(store.load())
        store.clear()
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }
}
