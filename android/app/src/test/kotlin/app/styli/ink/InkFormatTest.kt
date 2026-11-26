package app.styli.ink

import app.styli.ink.proto.Event
import app.styli.ink.proto.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Reads the file the Go core wrote (proto/testdata/sample.stylog): same bytes, same meaning.
class InkFormatTest {
    @Test
    fun readsTheGoSample() {
        val input = javaClass.classLoader!!.getResourceAsStream("sample.stylog")!!
        val records = generateSequence { Record.parseDelimitedFrom(input) }.toList()
        assertEquals(4, records.size)
        assertEquals(1, records[0].header.version)

        val page = records[1].event
        assertEquals(Event.KindCase.PAGE_SET, page.kindCase)
        assertEquals(21000, page.pageSet.width)

        val stroke = records[2].event.strokeAdded
        assertEquals(100, stroke.x0)
        assertEquals(listOf(5, -3), stroke.dxList)
        assertEquals(listOf(10, 20, 30), stroke.pressure.toByteArray().map { it.toInt() })
        assertEquals("tab", records[2].event.device)

        val erased = records[3].event
        assertEquals(Event.KindCase.STROKE_ERASED, erased.kindCase)
        assertEquals(records[2].event.id, erased.id)
        assertTrue(erased.seq == 3L)
    }
}
