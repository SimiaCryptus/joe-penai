package com.simiacryptus.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.simiacryptus.util.StringTools
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.simiacryptus.openai.*
import com.simiacryptus.util.JsonUtil
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.util.EntityUtils
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URL
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import javax.imageio.ImageIO

@Suppress("unused")
open class OpenAIClient(
    protected var key: String,
    private val apiBase: String = "https://api.openai.com/v1",
    private val logLevel: Level = Level.INFO
) : HttpClientManager() {

    open val metrics : Map<String, Any>
        get() = hashMapOf(
            "chats" to chatCounter.get(),
            "completions" to completionCounter.get(),
            "moderations" to moderationCounter.get(),
            "renders" to renderCounter.get(),
            "transcriptions" to transcriptionCounter.get(),
            "edits" to editCounter.get(),
            "tokens" to tokens.get(),
        )
    private val chatCounter = AtomicInteger(0)
    private val completionCounter = AtomicInteger(0)
    private val moderationCounter = AtomicInteger(0)
    private val renderCounter = AtomicInteger(0)
    private val transcriptionCounter = AtomicInteger(0)
    private val editCounter = AtomicInteger(0)
    private val tokens = AtomicInteger(0)

    fun getEngines(): Array<CharSequence?> {
        val engines = JsonUtil.objectMapper().readValue(
            get(apiBase + "/engines"),
            ObjectNode::class.java
        )
        val data = engines["data"]
        val items =
            arrayOfNulls<CharSequence>(data.size())
        for (i in 0 until data.size()) {
            items[i] = data[i]["id"].asText()
        }
        Arrays.sort(items)
        return items
    }

    private fun logComplete(completionResult: CharSequence) {
        log(
            logLevel, String.format(
                "Chat Completion:\n\t%s",
                completionResult.toString().replace("\n", "\n\t")
            )
        )
    }

    private fun logStart(completionRequest: CompletionRequest) {
        if (completionRequest.suffix == null) {
            log(
                logLevel, String.format(
                    "Text Completion Request\nPrefix:\n\t%s\n",
                    completionRequest.prompt.replace("\n", "\n\t")
                )
            )
        } else {
            log(
                logLevel, String.format(
                    "Text Completion Request\nPrefix:\n\t%s\nSuffix:\n\t%s\n",
                    completionRequest.prompt.replace("\n", "\n\t"),
                    completionRequest.suffix!!.replace("\n", "\n\t")
                )
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    protected fun post(url: String, json: String): String {
        val request = HttpPost(url)
        request.addHeader("Content-Type", "application/json")
        request.addHeader("Accept", "application/json")
        authorize(request)
        request.entity = StringEntity(json)
        return post(request)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun post(request: HttpPost): String = withClient { EntityUtils.toString(it.execute(request).entity) }

    @Throws(IOException::class)
    protected open fun authorize(request: HttpRequestBase) {
        request.addHeader("Authorization", "Bearer $key")
    }

    /**
     * Gets the response from the given URL.
     *
     * @param url The URL to GET the response from.
     * @return The response from the given URL.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    protected operator fun get(url: String?): String = withClient {
        val request = HttpGet(url)
        request.addHeader("Content-Type", "application/json")
        request.addHeader("Accept", "application/json")
        authorize(request)
        val response: HttpResponse = it.execute(request)
        val entity = response.entity
        EntityUtils.toString(entity)
    }

    fun transcription(wavAudio: ByteArray, prompt: String = ""): String = withReliability {
        withPerformanceLogging {
            transcriptionCounter.incrementAndGet()
            val url = "$apiBase/audio/transcriptions"
            val request = HttpPost(url)
            request.addHeader("Accept", "application/json")
            authorize(request)
            val entity = MultipartEntityBuilder.create()
            entity.setMode(HttpMultipartMode.RFC6532)
            entity.addBinaryBody("file", wavAudio, ContentType.create("audio/x-wav"), "audio.wav")
            entity.addTextBody("model", "whisper-1")
            entity.addTextBody("response_format", "verbose_json")
            if (prompt.isNotEmpty()) entity.addTextBody("prompt", prompt)
            request.entity = entity.build()
            val response = post(request)
            val jsonObject = Gson().fromJson(response, JsonObject::class.java)
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            try {
                val result = JsonUtil.objectMapper().readValue(response, TranscriptionResult::class.java)
                result.text!!
            } catch (e: Exception) {
                jsonObject.get("text").asString!!
            }
        }
    }

    fun transcriptionVerbose(wavAudio: ByteArray, prompt: String = ""): TranscriptionResult = withReliability {
        withPerformanceLogging {
            transcriptionCounter.incrementAndGet()
            val url = "$apiBase/audio/transcriptions"
            val request = HttpPost(url)
            request.addHeader("Accept", "application/json")
            authorize(request)
            val entity = MultipartEntityBuilder.create()
            entity.setMode(HttpMultipartMode.RFC6532)
            entity.addBinaryBody("file", wavAudio, ContentType.create("audio/x-wav"), "audio.wav")
            entity.addTextBody("model", "whisper-1")
            entity.addTextBody("response_format", "verbose_json")
            if (prompt.isNotEmpty()) entity.addTextBody("prompt", prompt)
            request.entity = entity.build()
            val response = post(request)
            val jsonObject = Gson().fromJson(response, JsonObject::class.java)
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            return@withPerformanceLogging JsonUtil.objectMapper().readValue(response, TranscriptionResult::class.java)
        }
    }

    fun render(prompt: String = "", resolution: Int = 1024, count: Int = 1): List<BufferedImage> = withReliability {
        withPerformanceLogging {
            renderCounter.incrementAndGet()
            val url = "$apiBase/images/generations"
            val request = HttpPost(url)
            request.addHeader("Accept", "application/json")
            request.addHeader("Content-Type", "application/json")
            authorize(request)
            val jsonObject = JsonObject()
            jsonObject.addProperty("prompt", prompt)
            jsonObject.addProperty("n", count)
            jsonObject.addProperty("size", "${resolution}x$resolution")
            request.entity = StringEntity(jsonObject.toString())
            val response = post(request)
            val jsonObject2 = Gson().fromJson(response, JsonObject::class.java)
            if (jsonObject2.has("error")) {
                val errorObject = jsonObject2.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            val dataArray = jsonObject2.getAsJsonArray("data")
            val images = ArrayList<BufferedImage>()
            for (i in 0 until dataArray.size()) {
                images.add(ImageIO.read(URL(dataArray[i].asJsonObject.get("url").asString)))
            }
            images
        }
    }

    @Throws(IOException::class)
    private fun processCompletionResponse(result: String): CompletionResponse {
        checkError(result)
        val response = JsonUtil.objectMapper().readValue(
            result,
            CompletionResponse::class.java
        )
        if (response.usage != null) {
            incrementTokens(response.usage!!.total_tokens)
        }
        return response
    }

    @Throws(IOException::class)
    protected fun processChatResponse(result: String): ChatResponse {
        checkError(result)
        val response = JsonUtil.objectMapper().readValue(
            result,
            ChatResponse::class.java
        )
        if (response.usage != null) {
            incrementTokens(response.usage!!.total_tokens)
        }
        return response
    }

    private fun checkError(result: String) {
        try {
            val jsonObject = Gson().fromJson(
                result,
                JsonObject::class.java
            )
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                val errorMessage = errorObject["message"].asString
                if (errorMessage.startsWith("That model is currently overloaded with other requests.")) {
                    throw RequestOverloadException(errorMessage)
                }
                maxTokenErrorMessage.find { it.matcher(errorMessage).matches() }?.let {
                    val matcher = it.matcher(errorMessage)
                    if (matcher.find()) {
                        val modelMax = matcher.group(1).toInt()
                        val request = matcher.group(2).toInt()
                        val messages = matcher.group(3).toInt()
                        val completion = matcher.group(4).toInt()
                        throw ModelMaxException(modelMax, request, messages, completion)
                    }
                }
                throw IOException(errorMessage)
            }
        } catch (e: com.google.gson.JsonSyntaxException) {
            throw IOException("Invalid JSON response: $result")
        }
    }

    class RequestOverloadException(message: String = "That model is currently overloaded with other requests.") :
        IOException(message)

    open fun incrementTokens(totalTokens: Int) {
        tokens.addAndGet(totalTokens)
    }

    open fun log(level: Level, msg: String) {
        val message = msg.trim().replace("\n", "\n\t")
        when (level) {
            Level.ERROR -> log.error(message)
            Level.WARN -> log.warn(message)
            Level.INFO -> log.info(message)
            Level.DEBUG -> log.debug(message)
            Level.TRACE -> log.debug(message)
            else -> log.debug(message)
        }
    }

    fun complete(
        completionRequest: CompletionRequest,
        model: String
    ): CompletionResponse = withReliability {
        withPerformanceLogging {
            completionCounter.incrementAndGet()
            logStart(completionRequest)
            val completionResponse = try {
                val request: String =
                    StringTools.restrictCharacterSet(
                        JsonUtil.objectMapper().writeValueAsString(completionRequest),
                        allowedCharset
                    )
                val result =
                    post("$apiBase/engines/$model/completions", request)
                processCompletionResponse(result)
            } catch (e: ModelMaxException) {
                completionRequest.max_tokens = (e.modelMax - e.messages) - 1
                val request: String =
                    StringTools.restrictCharacterSet(
                        JsonUtil.objectMapper().writeValueAsString(completionRequest),
                        allowedCharset
                    )
                val result =
                    post("$apiBase/engines/$model/completions", request)
                processCompletionResponse(result)
            }
            val completionResult = StringTools.stripPrefix(
                completionResponse.firstChoice.orElse("").toString().trim { it <= ' ' },
                completionRequest.prompt.trim { it <= ' ' })
            logComplete(completionResult)
            completionResponse
        }
    }

    fun chat(
        completionRequest: ChatRequest
    ): ChatResponse = withReliability {
        withPerformanceLogging {
            chatCounter.incrementAndGet()
            logStart(completionRequest)
            val url = "$apiBase/chat/completions"
            val completionResponse = try {
                processChatResponse(
                    post(
                        url, StringTools.restrictCharacterSet(
                            JsonUtil.objectMapper().writeValueAsString(completionRequest),
                            allowedCharset
                        )
                    )
                )
            } catch (e: ModelMaxException) {
                completionRequest.max_tokens = (e.modelMax - e.messages) - 1
                processChatResponse(
                    post(
                        url, StringTools.restrictCharacterSet(
                            JsonUtil.objectMapper().writeValueAsString(completionRequest),
                            allowedCharset
                        )
                    )
                )
            }
            logComplete(completionResponse.choices.first().message!!.content!!.trim { it <= ' ' })
            completionResponse
        }
    }

    private fun logStart(completionRequest: ChatRequest) {
        log(
            logLevel, String.format(
                "Chat Request\nPrefix:\n\t%s\n",
                JsonUtil.objectMapper().writeValueAsString(completionRequest).replace("\n", "\n\t")
            )
        )
    }

    fun moderate(text: String) = withReliability {
        withPerformanceLogging {
            moderationCounter.incrementAndGet()
            val body: String = try {
                JsonUtil.objectMapper().writeValueAsString(
                    mapOf(
                        "input" to StringTools.restrictCharacterSet(text, allowedCharset)
                    )
                )
            } catch (e: JsonProcessingException) {
                throw RuntimeException(e)
            }
            val result: String = try {
                this.post("$apiBase/moderations", body)
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            val jsonObject =
                Gson().fromJson(
                    result,
                    JsonObject::class.java
                )
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            val moderationResult =
                jsonObject.getAsJsonArray("results")[0].asJsonObject
            log(
                Level.DEBUG,
                String.format(
                    "Moderation Request\nText:\n%s\n\nResult:\n%s",
                    text.replace("\n", "\n\t"),
                    result
                )
            )
            if (moderationResult["flagged"].asBoolean) {
                val categoriesObj =
                    moderationResult["categories"].asJsonObject
                throw RuntimeException(
                    ModerationException(
                        "Moderation flagged this request due to " + categoriesObj.keySet()
                            .stream().filter { c: String? ->
                                categoriesObj[c].asBoolean
                            }.reduce { a: String, b: String -> "$a, $b" }
                            .orElse("???")
                    )
                )
            }
        }
    }

    fun edit(
        editRequest: EditRequest
    ): CompletionResponse = withReliability {
        withPerformanceLogging {
            editCounter.incrementAndGet()
            logStart(editRequest, logLevel)
            val request: String =
                StringTools.restrictCharacterSet(
                    JsonUtil.objectMapper().writeValueAsString(editRequest),
                    allowedCharset
                )
            val result = post("$apiBase/edits", request)
            val completionResponse = processCompletionResponse(result)
            logComplete(
                completionResponse.firstChoice.orElse("").toString().trim { it <= ' ' }
            )
            completionResponse
        }
    }

    private fun logStart(
        editRequest: EditRequest,
        level: Level
    ) {
        if (editRequest.input == null) {
            log(
                level, String.format(
                    "Text Edit Request\nInstruction:\n\t%s\n",
                    editRequest.instruction.replace("\n", "\n\t")
                )
            )
        } else {
            log(
                level, String.format(
                    "Text Edit Request\nInstruction:\n\t%s\nInput:\n\t%s\n",
                    editRequest.instruction.replace("\n", "\n\t"),
                    editRequest.input!!.replace("\n", "\n\t")
                )
            )
        }
    }

    companion object {
        val log = LoggerFactory.getLogger(OpenAIClient::class.java)
        val allowedCharset: Charset = Charset.forName("ASCII")
        private val maxTokenErrorMessage = listOf(
            Pattern.compile(
                """This model's maximum context length is (\d+) tokens. However, you requested (\d+) tokens \((\d+) in the messages, (\d+) in the completion\).*"""
            ),
            // This model's maximum context length is 4097 tokens, however you requested 80052 tokens (52 in your prompt; 80000 for the completion). Please reduce your prompt; or completion length.
            Pattern.compile(
                """This model's maximum context length is (\d+) tokens, however you requested (\d+) tokens \((\d+) in your prompt; (\d+) for the completion\).*"""
            )
        )
        fun isSanctioned(): Boolean {
            // Due to the invasion of Ukraine, Russia and allied groups are currently sanctioned.
            // Slava Ukraini!
            val locale = Locale.getDefault()
            // ISO 3166 - Russia
            if (locale.country.compareTo("RU", true) == 0) return true
            // ISO 3166 - Belarus
            if (locale.country.compareTo("BY", true) == 0) return true
            // ISO 639 - Russian
            if (locale.language.compareTo("ru", true) == 0) {
                // ISO 3166 - Ukraine
                if (locale.country.compareTo("UA", true) == 0) return false
                // ISO 3166 - United States
                if (locale.country.compareTo("US", true) == 0) return false
                // ISO 3166 - Britian
                if (locale.country.compareTo("GB", true) == 0) return false
                // ISO 3166 - United Kingdom
                if (locale.country.compareTo("UK", true) == 0) return false
                // ISO 3166 - Georgia
                if (locale.country.compareTo("GE", true) == 0) return false
                // ISO 3166 - Kazakhstan
                if (locale.country.compareTo("KZ", true) == 0) return false
                // ISO 3166 - Germany
                if (locale.country.compareTo("DE", true) == 0) return false
                // ISO 3166 - Poland
                if (locale.country.compareTo("PL", true) == 0) return false
                // ISO 3166 - Latvia
                if (locale.country.compareTo("LV", true) == 0) return false
                // ISO 3166 - Lithuania
                if (locale.country.compareTo("LT", true) == 0) return false
                // ISO 3166 - Estonia
                if (locale.country.compareTo("EE", true) == 0) return false
                // ISO 3166 - Moldova
                if (locale.country.compareTo("MD", true) == 0) return false
                // ISO 3166 - Armenia
                if (locale.country.compareTo("AM", true) == 0) return false
                // ISO 3166 - Azerbaijan
                if (locale.country.compareTo("AZ", true) == 0) return false
                // ISO 3166 - Kyrgyzstan
                if (locale.country.compareTo("KG", true) == 0) return false
                // ISO 3166 - Tajikistan
                if (locale.country.compareTo("TJ", true) == 0) return false
                // ISO 3166 - Turkmenistan
                if (locale.country.compareTo("TM", true) == 0) return false
                // ISO 3166 - Uzbekistan
                if (locale.country.compareTo("UZ", true) == 0) return false
                // ISO 3166 - Mongolia
                if (locale.country.compareTo("MN", true) == 0) return false
                return true
            }
            return false
        }

        // On classload, if isSanctioned==false, call System.exit(0)
        init {
            if (isSanctioned()) {
                log.error("You are not allowed to use this software. Slava Ukraini!")
                System.exit(0)
            }
        }

    }

}