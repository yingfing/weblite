package com.yingfing.weblite.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlBoxTest {

    @Test fun `完整链接原样保留`() {
        assertEquals("https://a.b/c", UrlBox.resolve("https://a.b/c"))
        assertEquals("http://x.tv", UrlBox.resolve("http://x.tv"))
        assertEquals("about:blank", UrlBox.resolve("about:blank"))
    }

    @Test fun `裸域名自动补 https`() {
        assertEquals("https://bilibili.com", UrlBox.resolve("bilibili.com"))
        assertEquals("https://192.168.1.1:8080/", UrlBox.resolve("192.168.1.1:8080/"))
        assertEquals("https://localhost:3000", UrlBox.resolve("localhost:3000"))
    }

    @Test fun `中文域名也认`() {
        assertEquals("https://哔哩哔哩.com", UrlBox.resolve("哔哩哔哩.com"))
    }

    @Test fun `普通词走搜索引擎`() {
        assertEquals(
            "https://www.bing.com/search?q=" + java.net.URLEncoder.encode("天气", "UTF-8"),
            UrlBox.resolve("天气"),
        )
        assertEquals(
            "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode("天气", "UTF-8"),
            UrlBox.resolve("天气", UrlBox.ENGINES[1]),
        )
    }

    @Test fun `带空格一定是搜索`() {
        val u = UrlBox.resolve("hello world")
        assertEquals("https://www.bing.com/search?q=hello+world", u)
    }

    @Test fun `hostOf 提取域名`() {
        assertEquals("www.bing.com", UrlBox.hostOf("https://www.bing.com/search?q=1"))
    }
}
