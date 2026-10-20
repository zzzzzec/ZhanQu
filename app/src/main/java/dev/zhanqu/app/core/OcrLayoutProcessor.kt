package dev.zhanqu.app.core

import dev.zhanqu.app.capture.OcrRemovedArtifact
import dev.zhanqu.app.capture.OcrTextBlock
import dev.zhanqu.app.capture.OcrTextLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrLayoutResult(val formattedText: String, val removedArtifacts: List<OcrRemovedArtifact>)

/**
 * Reconstructs a reading layout from coordinates, without correcting recognized characters.
 * Only whitespace is changed. Removed decorations remain in rawText/blocks and are enumerated.
 * Uncertain wraps retain a line break; missing geometry falls back to the exact engine text.
 * Each image is processed independently: this never guesses a continuation on another page.
 */
object OcrLayoutProcessor {
    private data class Line(val source: OcrTextLine, val block: Int, val order: Int) {
        val text get() = source.text.trim()
        val left get() = source.left
        val top get() = source.top
        val right get() = source.right
        val bottom get() = source.bottom
        val width get() = right - left
        val height get() = bottom - top
    }
    private data class Row(val lines: MutableList<Line>) {
        val left get() = lines.minOf { it.left }
        val top get() = lines.minOf { it.top }
        val right get() = lines.maxOf { it.right }
        val bottom get() = lines.maxOf { it.bottom }
        val height get() = bottom - top
        val blocks get() = lines.map { it.block }.toSet()
        fun text(): String = lines.sortedWith(compareBy({ it.left }, { it.order }))
            .map { it.text }.reduce { a, b -> a + separator(a, b) + b }
    }
    private data class Columns(val before: List<Line>, val left: List<Line>, val right: List<Line>, val after: List<Line>, val gap: Int)

    fun process(
        rawText: String,
        blocks: List<OcrTextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        pageIndex: Int? = null,
        totalPages: Int? = null
    ): OcrLayoutResult {
        val lines = blocks.flatMapIndexed { block, value ->
            value.lines.mapIndexed { index, line -> Line(line, block, index) }
        }.filter { it.text.isNotEmpty() }
        // Do not discard a text-only block or try to invent a location for it.
        if (imageWidth <= 0 || imageHeight <= 0 || lines.isEmpty() || lines.size > 1200 ||
            blocks.any { it.lines.isEmpty() && it.text.isNotBlank() } ||
            lines.any { it.width <= 0 || it.height <= 0 || it.left < 0 || it.top < 0 || it.right > imageWidth || it.bottom > imageHeight })
            return OcrLayoutResult(rawText, emptyList())

        val ordinary = lines.filter { it.text.length > 1 && it.width >= it.height }
        val typicalHeight = typicalHeight(ordinary.ifEmpty { lines }).coerceAtLeast(1)
        val removed = mutableListOf<OcrRemovedArtifact>()
        val retained = lines.filter { line ->
            val onlyLineInBlock = lines.count { it.block == line.block } == 1
            val reason = when {
                onlyLineInBlock && isCounter(line, lines, imageWidth, imageHeight, typicalHeight, pageIndex, totalPages) -> "page_counter"
                onlyLineInBlock && isVerticalRule(line, typicalHeight) -> "isolated_vertical_rule"
                else -> null
            }
            if (reason != null) removed += OcrRemovedArtifact(line.source.text, line.left, line.top, line.right, line.bottom, reason)
            reason == null
        }
        // An image containing only suspected decoration is ambiguous. Keep it untouched.
        if (retained.isEmpty()) return OcrLayoutResult(rawText, emptyList())
        return OcrLayoutResult(render(retained, typicalHeight, 0), removed)
    }

    private fun isCounter(line: Line, all: List<Line>, width: Int, height: Int, typical: Int, page: Int?, total: Int?): Boolean {
        if (page == null || total == null || page !in 1..total) return false
        val count = Regex("^(\\d{1,4})\\s*[/／]\\s*(\\d{1,4})$").matchEntire(line.text) ?: return false
        if (count.groupValues[1].toIntOrNull() != page || count.groupValues[2].toIntOrNull() != total) return false
        val corner = line.left >= width * .72 && line.right >= width * .80 &&
            (line.bottom <= height * .12 || line.top >= height * .88)
        val compact = line.width <= width * .18 && line.height <= min(height * .055, typical * 1.2)
        val margin = typical * .6
        val isolated = all.none { other -> other !== line && other.left < line.right + margin && other.right > line.left - margin &&
            other.top < line.bottom + margin && other.bottom > line.top - margin }
        return corner && compact && isolated
    }

    private fun isVerticalRule(line: Line, typical: Int): Boolean =
        line.text in setOf("|", "｜", "¦") && line.height >= typical * 1.8 &&
            line.width <= max(3.0, typical * .18) && line.height >= line.width * 8

    private fun render(lines: List<Line>, typical: Int, depth: Int): String {
        if (lines.isEmpty()) return ""
        val columns = if (depth < 8) findColumns(lines, typical) else null
        if (columns != null) {
            val leftHeight = typicalHeight(columns.left)
            val rightHeight = typicalHeight(columns.right)
            val afterHeight = columns.after.takeIf { it.isNotEmpty() }?.let(::typicalHeight)
            // Small inset/photo lettering beside a larger article is kept as its own region.
            // The article can widen below that inset; do not alternate the two on every row.
            val widensBelowInset = leftHeight < rightHeight * .7 && afterHeight != null &&
                max(rightHeight, afterHeight) <= min(rightHeight, afterHeight) * 1.25
            val zones = if (widensBelowInset) listOf(columns.before, columns.left, columns.right + columns.after)
                else listOf(columns.before, columns.left, columns.right, columns.after)
            return zones.filter { it.isNotEmpty() }.joinToString("\n\n") { render(it, typical, depth + 1) }
        }
        val rows = rows(lines)
        val blockBounds = lines.groupBy { it.block }.mapValues { (_, value) -> value.minOf { it.left } to value.maxOf { it.right } }
        val regionLeft = rows.minOf { it.left }
        val regionRight = rows.maxOf { it.right }
        return buildString {
            rows.forEachIndexed { index, row ->
                val value = row.text()
                if (index > 0) {
                    val previous = rows[index - 1]
                    val previousText = previous.text()
                    val shared = previous.blocks.intersect(row.blocks)
                    val bounds = if (shared.isNotEmpty()) shared.mapNotNull { blockBounds[it] } else emptyList()
                    val left = bounds.minOfOrNull { it.first } ?: regionLeft
                    val right = bounds.maxOfOrNull { it.second } ?: regionRight
                    val smallHeight = min(previous.height, row.height).toDouble()
                    val gap = row.top - previous.bottom
                    val sameSize = max(previous.height, row.height) <= smallHeight * 1.35
                    val aligned = abs(row.left - left) <= smallHeight * .65 || abs(row.left - previous.left) <= smallHeight * .65
                    val reachesEnd = right - previous.right <= smallHeight * 1.25
                    // Close, aligned lines that fill their block/column are a geometric wrap.
                    val wrap = sameSize && gap >= -smallHeight * .2 && gap <= smallHeight * .9 && aligned && reachesEnd && !startsListItem(value)
                    when {
                        wrap -> append(separator(previousText, value))
                        gap > smallHeight * .9 || !sameSize || startsListItem(value) -> append("\n\n")
                        else -> append('\n')
                    }
                }
                append(value)
            }
        }
    }

    /** A gutter needs repeated support on both sides; spanning headings may only sit outside it. */
    private fun findColumns(lines: List<Line>, typical: Int): Columns? {
        if (lines.size < 4) return null
        val edges = lines.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        val regionWidth = lines.maxOf { it.right } - lines.minOf { it.left }
        var best: Columns? = null
        for (pair in edges.zipWithNext()) {
            val x = (pair.first.toDouble() + pair.second) / 2
            val left = lines.filter { it.right <= x }
            val right = lines.filter { it.left >= x }
            if (left.size < 2 || right.size < 2) continue
            val gap = right.minOf { it.left } - left.maxOf { it.right }
            if (gap <= (best?.gap ?: 0)) continue
            val smallerHeight = min(typicalHeight(left), typicalHeight(right))
            val minimumGap = max(smallerHeight * 1.2, regionWidth * .025)
            if (gap < minimumGap) continue
            if (left.maxOf { it.right } - left.minOf { it.left } < typical * 3 ||
                right.maxOf { it.right } - right.minOf { it.left } < typical * 3) continue
            val overlap = min(left.maxOf { it.bottom }, right.maxOf { it.bottom }) - max(left.minOf { it.top }, right.minOf { it.top })
            if (overlap < smallerHeight * 1.8 || rowCount(left) < 2 || rowCount(right) < 2) continue
            val middleTop = min(left.minOf { it.top }, right.minOf { it.top })
            val middleBottom = max(left.maxOf { it.bottom }, right.maxOf { it.bottom })
            val spanning = lines.filter { it.right > x && it.left < x }
            // A line that crosses the proposed gutter inside the body disproves that split.
            if (spanning.any { it.bottom > middleTop && it.top < middleBottom }) continue
            best = Columns(spanning.filter { it.bottom <= middleTop }, left, right,
                spanning.filter { it.top >= middleBottom }, gap)
        }
        return best
    }

    private fun rowCount(lines: List<Line>) = rows(lines).size

    private fun rows(lines: List<Line>): List<Row> {
        val result = mutableListOf<Row>()
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }, { it.block }, { it.order }))) {
            val row = result.lastOrNull { candidate ->
                val overlap = min(candidate.bottom, line.bottom) - max(candidate.top, line.top)
                val small = min(candidate.height, line.height)
                val centerDistance = abs((candidate.top.toDouble() + candidate.bottom - line.top - line.bottom) / 2)
                overlap >= small * .6 && centerDistance <= small * .35 && max(candidate.height, line.height) <= small * 1.4 &&
                    // Overlapping text boxes can be separate readings; do not collapse them.
                    candidate.lines.all { it.right <= line.left || it.left >= line.right }
            }
            if (row == null) result += Row(mutableListOf(line)) else row.lines += line
        }
        return result.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun startsListItem(text: String): Boolean = Regex("^(?:[•●▪◦]|[-*]\\s|\\d{1,3}[.)、]\\s|[（(]\\d{1,3}[）)]\\s)").containsMatchIn(text)

    /** Insert a word space between Latin/number fragments; never invent punctuation or words. */
    private fun separator(a: String, b: String): String {
        if (a.isEmpty() || b.isEmpty() || a.last().isWhitespace() || b.first().isWhitespace()) return ""
        val before = a.last()
        val after = b.first()
        return if (before.isLetterOrDigit() && after.isLetterOrDigit() && !isCjk(before) && !isCjk(after)) " " else ""
    }

    private fun isCjk(char: Char): Boolean = char in '\u2e80'..'\u9fff' || char in '\uf900'..'\ufaff' || char in '\u3040'..'\u30ff' || char in '\uac00'..'\ud7af'
    /** Pixel-area weighting prevents many tiny photo labels from setting the article's scale. */
    private fun typicalHeight(lines: List<Line>): Int {
        val sorted = lines.sortedBy { it.height }
        val total = sorted.sumOf { it.width.toLong() * it.height }
        var weight = 0L
        for (line in sorted) {
            weight += line.width.toLong() * line.height
            if (weight * 2 >= total) return line.height
        }
        return sorted.last().height
    }
}
