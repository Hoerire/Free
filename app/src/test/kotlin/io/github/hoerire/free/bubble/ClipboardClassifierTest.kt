package io.github.hoerire.free.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 分享链接要能认到具体应用，而不是一律落到浏览器。 */
class ClipboardClassifierTest {
    private fun link(text: String): ClipboardClassifier.Result =
        ClipboardClassifier.classifyAll(text).first { it.kind == ClipboardClassifier.KIND_LINK }

    private fun shopping(text: String): ClipboardClassifier.Result =
        ClipboardClassifier.classifyAll(text).first { it.kind == ClipboardClassifier.KIND_SHOPPING }

    @Test
    fun shortDramaShareLinkMapsToHongguo() {
        // 红果短剧的分享短链：系统 App Link 没通过验证，只能靠域名表兜住。
        assertEquals(
            "com.phoenix.read",
            link("https://applink.changdunovel.com/abc123").appPackage,
        )
        assertEquals(
            "com.phoenix.read",
            link("https://novel8662.ugurl.cn/z/abc").appPackage,
        )
    }

    @Test
    fun realHongguoShareLinkWithQueryMapsToApp() {
        val url = "https://novelquickapp.com/hongguo/ug/pages/video-animation-share" +
            "?encrypt_did=MDIEDLbvm2c0Ybgdl1spiAQQjN%2FG9nhE4kvuT9yMvwj%2FbwQQb6v8vNh4ehc%2F" +
            "Cd7Xqytojg%3D%3D&video_id=7703520775487097880&vid=7703522365166062617&share_type=4"
        val result = ClipboardClassifier.classifyAll(url).first { it.kind == ClipboardClassifier.KIND_LINK }
        assertEquals("com.phoenix.read", result.appPackage)
    }

    @Test
    fun commonShareLinksMapToTheirApps() {
        assertEquals(
            "com.ss.android.ugc.aweme",
            link("7.88 复制打开抖音 https://v.douyin.com/iRNBho6/ xyz").appPackage,
        )
        assertEquals(
            "tv.danmaku.bili",
            link("https://b23.tv/AbCdEf").appPackage,
        )
        assertEquals(
            "com.smile.gifmaker",
            link("https://v.kuaishou.com/abc").appPackage,
        )
        assertEquals(
            "com.ss.android.ugc.aweme",
            link("https://www.iesdouyin.com/share/video/1").appPackage,
        )
    }

    @Test
    fun shoppingLinkKeepsKindAndApp() {
        val result = shopping("https://m.tb.cn/h.abc")
        assertEquals("com.taobao.taobao", result.appPackage)
        assertEquals(
            "com.xunmeng.pinduoduo",
            shopping("https://mobile.yangkeduo.com/goods.html?goods_id=1").appPackage,
        )
    }

    @Test
    fun unknownHostStaysGeneric() {
        assertNull(link("https://example.invalid/abc").appPackage)
    }

    @Test
    fun deepLinksCarryTheirSchemeApp() {
        val coolapk = ClipboardClassifier.classifyAll("coolapk://feed/123")
            .first { it.kind == ClipboardClassifier.KIND_APP_LINK }
        assertEquals("com.coolapk.market", coolapk.appPackage)

        val taobao = ClipboardClassifier.classifyAll("tbopen://m.taobao.com/tbopen/index.html?a=1")
            .first { it.kind == ClipboardClassifier.KIND_APP_LINK }
        assertEquals("com.taobao.taobao", taobao.appPackage)
    }
}
