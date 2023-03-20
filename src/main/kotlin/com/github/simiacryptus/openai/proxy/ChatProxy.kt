package com.github.simiacryptus.openai.proxy

import com.github.simiacryptus.openai.core.CoreAPI
import org.slf4j.event.Level
import java.time.Duration

class ChatProxy(
    apiKey: String,
    private val model: String = "gpt-3.5-turbo",
    private val maxTokens: Int = 3500,
    private val temperature: Double = 0.7,
    private val verbose: Boolean = false,
    private val moderated: Boolean = true,
    base: String = "https://api.openai.com/v1",
    apiLog: String? = null,
    logLevel: Level
) : GPTProxyBase(apiLog, 3) {
    val api: CoreAPI

    init {
        api = CoreAPI(base, apiKey, logLevel)
    }

    override fun complete(prompt: ProxyRequest, vararg examples: ProxyRecord): String {
        if (verbose) println(prompt)
        val request = com.github.simiacryptus.openai.core.ChatRequest()
        request.messages = (
                listOf(
                    com.github.simiacryptus.openai.core.ChatMessage(
                        com.github.simiacryptus.openai.core.ChatMessage.Role.system, """
                |You are a JSON-RPC Service serving the following method:
                |${prompt.methodName}
                |Requests contain the following arguments:
                |${prompt.argList.keys.joinToString("\n  ")}
                |Responses are of type:
                |${prompt.responseType}
                |Responses are expected to be a single JSON object
                |All input arguments are optional
                |""".trimMargin().trim()
                    )
                ) +
                        examples.flatMap {
                            listOf(
                                com.github.simiacryptus.openai.core.ChatMessage(
                                    com.github.simiacryptus.openai.core.ChatMessage.Role.user,
                                    argsToString(it.argList)
                                ),
                                com.github.simiacryptus.openai.core.ChatMessage(
                                    com.github.simiacryptus.openai.core.ChatMessage.Role.assistant,
                                    it.response
                                )
                            )
                        } +
                        listOf(
                            com.github.simiacryptus.openai.core.ChatMessage(
                                com.github.simiacryptus.openai.core.ChatMessage.Role.user,
                                argsToString(prompt.argList)
                            )
                        )
                ).toTypedArray()
        request.model = model
        request.max_tokens = maxTokens
        request.temperature = temperature
        val completion = api.withTimeout(Duration.ofMinutes(3)) {
            if (moderated) api.moderate(toJson(request))
            api.chat(request).response.get().toString()
        }
        if (verbose) println(completion)
        val trimPrefix = trimPrefix(completion)
        val trimSuffix = trimSuffix(trimPrefix.first)
        return trimSuffix.first
    }

    private fun trimPrefix(completion: String): Pair<String, String> {
        val start = completion.indexOf('{')
        if (start < 0) {
            return completion to ""
        } else {
            val substring = completion.substring(start)
            return substring to completion.substring(0, start)
        }
    }

    private fun trimSuffix(completion: String): Pair<String, String> {
        val end = completion.lastIndexOf('}')
        if (end < 0) {
            return completion to ""
        } else {
            val substring = completion.substring(0, end + 1)
            return substring to completion.substring(end + 1)
        }
    }

    private fun argsToString(argList: Map<String, String>) =
        "{" + argList.entries.joinToString(",\n", transform = { (argName, argValue) ->
            """"$argName": $argValue"""
        }) + "}"
}