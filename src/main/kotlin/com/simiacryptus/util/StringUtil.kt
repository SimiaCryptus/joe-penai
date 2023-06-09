package com.simiacryptus.util

import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.math.abs

object StringUtil {

    @JvmStatic
    fun indentJoin(fields: List<Any>, indent: String = "\t"): String {
        val joinToString = fields.joinToString("\n$indent")
            { it.toString().replace("\n", "\n$indent") }
        return "{\n$indent$joinToString\n}"
    }

    @JvmStatic
    fun stripUnbalancedTerminators(input: CharSequence): CharSequence {
        var openCount = 0
        var inQuotes = false
        val output = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes
            } else if (inQuotes && c == '\\') {
                // Skip the next character
                i++
            } else if (!inQuotes) {
                when (c) {
                    '{', '[', '(' -> openCount++
                    '}', ']', ')' -> openCount--
                }
            }
            if (openCount >= 0) {
                output.append(c)
            } else {
                openCount++ // Dropping character, undo counting close bracket
            }
            i++
        }
        return output.toString()
    }

    @JvmStatic
    fun stripPrefix(text: CharSequence, prefix: CharSequence): CharSequence {
        val startsWith = text.toString().startsWith(prefix.toString())
        return if (startsWith) {
            text.toString().substring(prefix.length)
        } else {
            text.toString()
        }
    }

    @JvmStatic
    fun trimPrefix(text: CharSequence): CharSequence {
        val prefix = getWhitespacePrefix(text)
        return stripPrefix(text, prefix)
    }

    @JvmStatic
    fun trimSuffix(text: CharSequence): String {
        val suffix = getWhitespaceSuffix(text)
        return stripSuffix(text, suffix)
    }

    @JvmStatic
    fun stripSuffix(text: CharSequence, suffix: CharSequence): String {
        val endsWith = text.toString().endsWith(suffix.toString())
        return if (endsWith) {
            text.toString().substring(0, text.length - suffix.length)
        } else {
            text.toString()
        }
    }

    @JvmStatic
    fun lineWrapping(description: CharSequence, width: Int): String {
        val output = StringBuilder()
        val lines = description.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        var lineLength = 0
        for (line in lines) {
            val sentenceLength = AtomicInteger(lineLength)
            var sentenceBuffer = wrapSentence(line, width, sentenceLength)
            if (lineLength + sentenceBuffer.length > width && sentenceBuffer.length < width) {
                output.append("\n")
                lineLength = 0
                sentenceLength.set(lineLength)
                sentenceBuffer = wrapSentence(line, width, sentenceLength)
            } else {
                output.append(" ")
                sentenceLength.addAndGet(1)
            }
            output.append(sentenceBuffer)
            lineLength = sentenceLength.get()
        }
        return output.toString()
    }

    @JvmStatic
    private fun wrapSentence(line: CharSequence, width: Int, xPointer: AtomicInteger): String {
        val sentenceBuffer = StringBuilder()
        val words = line.toString().split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (word in words) {
            if (xPointer.get() + word.length > width) {
                sentenceBuffer.append("\n")
                xPointer.set(0)
            } else {
                sentenceBuffer.append(" ")
                xPointer.addAndGet(1)
            }
            sentenceBuffer.append(word)
            xPointer.addAndGet(word.length)
        }
        return sentenceBuffer.toString()
    }

    @JvmStatic
    fun toString(ints: IntArray): CharSequence {
        val chars = CharArray(ints.size)
        for (i in ints.indices) {
            chars[i] = ints[i].toChar()
        }
        return String(chars)
    }

    @JvmStatic
    fun getWhitespacePrefix(vararg lines: CharSequence): CharSequence {
        return Arrays.stream(lines)
            .map { l: CharSequence ->
                toString(
                    l.chars().takeWhile { codePoint: Int ->
                        Character.isWhitespace(
                            codePoint
                        )
                    }.toArray()
                )
            }
            .filter { x: CharSequence -> x.isNotEmpty() }
            .min(Comparator.comparing { obj: CharSequence -> obj.length }).orElse("")
    }

    @JvmStatic
    fun getWhitespacePrefix2(vararg lines: CharSequence): CharSequence {
        return Arrays.stream(lines)
            .map { l: CharSequence ->
                toString(
                    l.chars().takeWhile { codePoint: Int ->
                        Character.isWhitespace(
                            codePoint
                        )
                    }.toArray()
                )
            }
            .min(Comparator.comparing { obj: CharSequence -> obj.length }).orElse("")
    }

    @JvmStatic
    fun getWhitespaceSuffix(vararg lines: CharSequence): String {
        return reverse(Arrays.stream(lines)
            .map { obj: CharSequence? -> reverse(obj!!) }
            .map { l: CharSequence ->
                toString(
                    l.chars().takeWhile { codePoint: Int ->
                        Character.isWhitespace(
                            codePoint
                        )
                    }.toArray()
                )
            }
            .max(Comparator.comparing { obj: CharSequence -> obj.length }).orElse("")
        ).toString()
    }

    @JvmStatic
    private fun reverse(l: CharSequence): CharSequence {
        return StringBuffer(l).reverse().toString()
    }

    @JvmStatic
    fun trim(items: List<CharSequence>, max: Int, preserveHead: Boolean): List<CharSequence> {
        var items = items
        items = ArrayList(items)
        val random = Random()
        while (items.size > max) {
            val index = random.nextInt(items.size)
            if (preserveHead && index == 0) continue
            items.removeAt(index)
        }
        return items
    }

    @JvmStatic
    fun transposeMarkdownTable(table: String, inputHeader: Boolean, outputHeader: Boolean): String {
        val cells = parseMarkdownTable(table, inputHeader)
        val transposedTable = StringBuilder()
        var columns = cells[0].size
        if (outputHeader) columns = columns + 1
        for (column in 0 until columns) {
            transposedTable.append("|")
            for (cell in cells) {
                var cellValue: String = if (outputHeader) {
                    if (column < 1) {
                        cell[column].toString().trim { it <= ' ' }
                    } else if (column == 1) {
                        "---"
                    } else if (column - 1 >= cell.size) {
                        ""
                    } else {
                        cell[column - 1].toString().trim { it <= ' ' }
                    }
                } else {
                    cell[column].toString().trim { it <= ' ' }
                }
                transposedTable.append(" ").append(cellValue).append(" |")
            }
            transposedTable.append("\n")
        }
        return transposedTable.toString()
    }

    @JvmStatic
    private fun parseMarkdownTable(table: String, removeHeader: Boolean): Array<Array<CharSequence>> {
        val rows = Arrays.stream(table.split("\n".toRegex()).map { it.trim() }.dropLastWhile { it.isEmpty() }
            .toTypedArray()).map { x: String ->
            Arrays.stream(x.split("\\|".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()).filter { cell: String -> cell.isNotEmpty() }
                .collect(Collectors.toList<CharSequence>()).toTypedArray()
        }.collect(
            Collectors.toCollection { ArrayList() }
        )
        if (removeHeader) {
            rows.removeAt(1)
        }
        return rows.toTypedArray()
    }

    @JvmStatic
    fun getPrefixForContext(text: String, idealLength: Int): CharSequence {
        return getPrefixForContext(text, idealLength, ".", "\n", ",", ";")
    }

    /**
     * Get the prefix for the given context.
     *
     * @param text        The text to get the prefix from.
     * @param idealLength The ideal length of the prefix.
     * @param delimiters  The delimiters to split the text by.
     * @return The prefix for the given context.
     */
    @JvmStatic
    fun getPrefixForContext(text: String, idealLength: Int, vararg delimiters: CharSequence?): CharSequence {
        return getSuffixForContext(text.reversed(), idealLength, *delimiters).reversed()
    }

    @JvmStatic
    fun getSuffixForContext(text: String, idealLength: Int): CharSequence {
        return getSuffixForContext(text, idealLength, ".", "\n", ",", ";")
    }

    @JvmStatic
    fun restrictCharacterSet(text: String, charset: Charset): String {
        val encoder = charset.newEncoder()
        val sb = StringBuilder()
        text.toCharArray().filter(encoder::canEncode).forEach(sb::append)
        return sb.toString()
    }


    @JvmStatic
    fun replaceAll(
        replaceString: String,
        vararg replacements: Pair<String, String>
    ) = replacements.fold(replaceString) { acc, (a, b) -> acc.replace(a, b) }

    @JvmStatic
    fun replaceAllNonOverlapping(
        replaceString: String,
        vararg replacements: Pair<String, String>
    ): String {
        val joinedPattern = replacements.joinToString("|") { Pattern.quote(it.first) }.toRegex()
        return joinedPattern.replace(replaceString) { result ->
            val charSequence: CharSequence =
                replacements.find { it.first.compareTo(result.value, true) == 0 }?.second ?: result.value
            charSequence
        }
    }

    /**
     *
     * Get the suffix for the given context.
     *
     * @param text The text to get the suffix from.
     * @param idealLength The ideal length of the suffix.
     * @param delimiters The delimiters to use when splitting the text.
     * @return The suffix for the given context.
     */
    @JvmStatic
    fun getSuffixForContext(text: String, idealLength: Int, vararg delimiters: CharSequence?): CharSequence {
        // Create a list of candidates by splitting the text by each of the delimiters
        val candidates = Stream.of(*delimiters).flatMap { d: CharSequence? ->
            // Create a string builder to store the split strings
            val sb = StringBuilder()
            // Split the text by the delimiter
            val split = text.split(Pattern.quote(d.toString()).toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            // Iterate through the split strings in reverse order
            for (i in split.indices.reversed()) {
                val s = split[i]
                // If the length of the string builder is closer to the ideal length than the length of the string builder plus the current string, break
                if (abs(sb.length - idealLength) < abs(sb.length + s.length - idealLength)) break
                // If the string builder is not empty or the text ends with the delimiter, add the delimiter to the string builder
                if (sb.isNotEmpty() || text.endsWith(d.toString())) sb.insert(0, d)
                // Add the current string to the string builder
                sb.insert(0, s)
                // If the length of the string builder is greater than the ideal length, break
                if (sb.length > idealLength) {
                    //if (i > 0) sb.insert(0, d);
                    break
                }
            }
            // If the split strings are empty, return an empty stream
            if (split.isEmpty()) return@flatMap Stream.empty<String>()
            // Return a stream of the string builder
            Stream.of(sb.toString())
        }.collect(Collectors.toList())
        // Return the string with the closest length to the ideal length
        return candidates.stream().min(Comparator.comparing { s: CharSequence ->
            abs(
                s.length - idealLength
            )
        }).orElse("")
    }
}