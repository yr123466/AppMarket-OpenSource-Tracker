package top.app.market.data.remote.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GooglePlayHtmlTest {
    @Test
    fun extractsVersionFromInitDataBlock() {
        // 取自真实详情页 AF_initDataCallback 的片段：`]]],"2.26.16.72",null,[[[`
        val html = """...xdr8tXxXyUdq4tVGtfgeKuptveGdP1srxaHVrNPzOYcfaEQ"]]],"2.26.16.72",null,[[["vaf_phase1_file_sharing",[1]]]..."""
        assertEquals("2.26.16.72", extractPlayVersionName(html))
    }

    @Test
    fun extractsShortVersion() {
        assertEquals("1.7", extractPlayVersionName("""]]],"1.7",null,[[[..."""))
    }

    @Test
    fun returnsNullWhenNoVersionBlock() {
        assertNull(extractPlayVersionName("<html><body>no version here</body></html>"))
    }
}
