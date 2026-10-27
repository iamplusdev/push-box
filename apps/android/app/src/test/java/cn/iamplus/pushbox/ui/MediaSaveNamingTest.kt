package cn.iamplus.pushbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.4 存图落盘的两个纯函数：格式判定与文件名。
 *
 * 服务端转存时保留原格式，早先的代码一律写成 .jpg —— 相册里打开会是一张黑图。
 * 文件名则直接拼了 msg_id，而那是服务端下发的字符串：它只要带一个 `/` 或 `..`，
 * 就等于让外部输入决定写到哪去。
 */
class MediaSaveNamingTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun formatsAreSniffedFromMagicBytes() {
        assertEquals("image/png", imageMimeOf(
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/gif", imageMimeOf(ascii("GIF89a")))
        assertEquals("image/webp", imageMimeOf(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
    }

    @Test
    fun jpegIsTheFallback() {
        assertEquals("image/jpeg", imageMimeOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        // 短到读不出文件头也不能抛：这时只能当 jpg 写下去
        assertEquals("image/jpeg", imageMimeOf(byteArrayOf()))
        assertEquals("image/jpeg", imageMimeOf(byteArrayOf('R'.code.toByte())))
    }

    @Test
    fun fileNameTakesItsExtensionFromTheSniffedFormat() {
        assertEquals("PushBox_msg-1_2.png", imageFileName("msg-1", 2, "image/png"))
        assertEquals("PushBox_msg-1_0.webp", imageFileName("msg-1", 0, "image/webp"))
        assertEquals("PushBox_msg-1_3.jpg", imageFileName("msg-1", 3, "image/jpeg"))
    }

    @Test
    fun msgIdCannotEscapeThePicturesDirectory() {
        val name = imageFileName("../../sdcard/DCIM/x", 0, "image/jpeg")
        assertFalse("路径分隔符必须被过滤掉", name.contains("/") || name.contains("\\"))
        assertFalse("不能残留 .. 段", name.contains(".."))
        assertEquals("PushBox_sdcardDCIMx_0.jpg", name)
    }

    @Test
    fun tooLongOrUnprintableMsgIdStillYieldsAName() {
        val long = "a".repeat(200)
        val name = imageFileName(long, 1, "image/png")
        assertTrue("名字要有上限，别把 200 个字符带进目录", name.length < 64)
        assertTrue(name.endsWith("_1.png"))
        // 全被过滤掉时也得有可用的名字
        assertEquals("PushBox_img_0.jpg", imageFileName("///", 0, "image/jpeg"))
    }
}
