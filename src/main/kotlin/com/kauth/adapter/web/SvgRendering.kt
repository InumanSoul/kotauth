package com.kauth.adapter.web

import kotlinx.html.HTMLTag
import kotlinx.html.unsafe
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

private const val RESOURCE_BASE_PATH = "static/icons"
private val SVG_RESOURCE_CACHE = ConcurrentHashMap<String, String>()

private fun loadSvgResource(resourcePath: String): String =
    SVG_RESOURCE_CACHE.getOrPut(resourcePath) {
        SvgRendering::class.java.classLoader
            .getResourceAsStream(resourcePath)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText().trim() }
            .orEmpty()
    }

private fun String.escapeHtmlAttribute(): String =
    this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

object SvgRendering {
    fun inlineSvgIconMarkup(
        iconName: String,
        ariaLabel: String,
        cssClass: String = "",
    ): String {
        val resourcePath = "$RESOURCE_BASE_PATH/$iconName.svg"
        val rawSvg = loadSvgResource(resourcePath)
        if (rawSvg.isBlank()) return ""

        val classAttribute = if (cssClass.isBlank()) "" else " class=\"${cssClass.escapeHtmlAttribute()}\""
        return rawSvg.replaceFirst(
            "<svg",
            "<svg$classAttribute role=\"img\" aria-label=\"${ariaLabel.escapeHtmlAttribute()}\" focusable=\"false\"",
        )
    }
}

fun HTMLTag.inlineSvgIcon(
    iconName: String,
    ariaLabel: String,
    cssClass: String = "",
) {
    val svgMarkup = SvgRendering.inlineSvgIconMarkup(iconName = iconName, ariaLabel = ariaLabel, cssClass = cssClass)
    if (svgMarkup.isBlank()) return
    unsafe {
        +svgMarkup
    }
}
