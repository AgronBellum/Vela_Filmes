package com.tanasi.retrofit_jsoup.converter

import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import retrofit2.Converter

class JsoupConverter(
    private val baseUri: String,
) : Converter<ResponseBody, Document?> {

    override fun convert(value: ResponseBody): Document? {
        return value.use { responseBody ->

            // Identifica o tipo de conteúdo sem acessar
            // diretamente a propriedade subtype do OkHttp.
            val mimeType = responseBody.contentType()
                ?.toString()
                ?.substringBefore(';')
                ?.trim()
                .orEmpty()

            val isXml = mimeType
                .substringAfter('/', "")
                .contains("xml", ignoreCase = true)

            // Seleciona o parser adequado.
            val parser = if (isXml) {
                Parser.xmlParser()
            } else {
                Parser.htmlParser()
            }

            // Lê o conteúdo respeitando o charset da resposta.
            val body = responseBody.string()

            // Converte o conteúdo em documento Jsoup.
            Jsoup.parse(body, baseUri, parser)
        }
    }
}
