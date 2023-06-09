package com.simiacryptus.labs

import com.simiacryptus.openai.OpenAIClient
import com.simiacryptus.util.JsonUtil
import java.awt.image.BufferedImage
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.reflect.KClass

open class GenerationReportBase<T:Any>(
    kClass: KClass<T>,
) {
    val proxy by lazy {
        ProxyTest.chatProxy(
            kClass.java,
            "./reports/api.${
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            }.log.json"
        )
    }

    val outputDir = File("./reports")
    fun runReport(prefix: String, fn: (T, (Any?) -> Unit, (Any?) -> Unit) -> Unit) {
        if (OpenAIClient.keyTxt.isBlank()) return
        val markdownOutputFile = File(
            outputDir,
            "${prefix}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"
        )
        BufferedWriter(markdownOutputFile.writer()).use { writer ->
            fun out(s: Any?) {
                if (null == s) return
                println(s.toString())
                writer.write(s.toString())
                writer.newLine()
                writer.flush()
            }

            fun logJson(obj: Any?) {
                if (null == obj) return
                out(
                    """
                            |```json
                            |${JsonUtil.toJson(obj)}
                            |```
                            |""".trimMargin()
                )
            }

            val file = File("$prefix.examples.json")
            if (file.exists()) proxy.addExamples(file)
            fn(proxy.create(), ::logJson, ::out)

            // Print proxy's metrics
            logJson(proxy.metrics)

        }
    }

    fun writeImage(image: BufferedImage): String {
        val imageDir = File(outputDir, "images")
        imageDir.mkdirs()
        val file = File.createTempFile("image", ".png", imageDir)
        ImageIO.write(image, "png", file)
        return file.toRelativeString(outputDir)
    }
}