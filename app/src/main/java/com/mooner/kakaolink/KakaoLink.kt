package com.mooner.kakaolink

import android.util.Patterns
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class KakaoLink(
    private val apiKey:String,
    location:String
) {
    private var kakaoStatic:String = "sdk/1.36.6 os/javascript lang/en-US device/Win32 origin/"
    private lateinit var cookies:HashMap<String,String>
    private lateinit var referrer:String

    init {
        if (apiKey.length != 32) throw IllegalArgumentException("API KEY 의 길이가 올바르지 않습니다.")
        if (!Patterns.WEB_URL.matcher(location).find()) throw IllegalArgumentException("도메인 주소의 형식이 올바르지 않습니다.")

        kakaoStatic += URLEncoder.encode(location,"utf-8")
    }

    fun login(email:String,pw:String,onLoginSuccess:(()->Unit)? = null) {
        val loginResponse = Jsoup.connect("https://sharer.kakao.com/talk/friends/picker/link").apply {
            data("app_key",apiKey)
            data("validation_action","default")
            data("validation_params","{}")
            data("ka",kakaoStatic)
            data("lcba","")
            method(Connection.Method.POST)
        }.execute()

        when(loginResponse.statusCode()) {
            401 -> throw IllegalStateException("API KEY가 유효하지 않습니다.")
            200 -> {
                referrer = loginResponse.url().toExternalForm()
                val doc = loginResponse.parse()
                val cryptoKey = doc.select("input[name=p]").attr("value")

                cookies = hashMapOf(
                    "_kadu" to loginResponse.cookie("_kadu"),
                    "_kadub" to loginResponse.cookie("_kadub"),
                    "_maldive_oauth_webapp_session" to loginResponse.cookie("_maldive_oauth_webapp_session"),
                    "TIARA" to Jsoup.connect("https://track.tiara.kakao.com/queen/footsteps")
                        .ignoreContentType(true)
                        .execute()
                        .cookie("TIARA")
                )

                val response = Jsoup.connect("https://accounts.kakao.com/weblogin/authenticate.json").apply {
                    referrer(referrer)
                    cookies(cookies)
                    data("os","web")
                    data("webview_v","2")
                    data("email",encrypt(email,cryptoKey))
                    data("password",encrypt(pw,cryptoKey))
                    data("stay_signed_in","true")
                    data("continue",URLDecoder.decode(referrer.split("=")[1],"utf-8"))
                    data("third","false")
                    data("k","true")
                    method(Connection.Method.POST)
                    ignoreContentType(true)
                    ignoreHttpErrors(true)
                }.execute()
                when(Json { isLenient= true }.decodeFromString<Map<String,String>>(response.body())["status"]) {
                    "-450" -> throw IllegalStateException("이메일 또는 비밀번호가 올바르지 않습니다.")
                    "-481","-484" -> throw IllegalStateException(response.body())
                    "0" -> {
                        cookies.putAll(
                            mapOf(
                                "_kawlt" to response.cookie("_kawlt"),
                                "_kawltea" to response.cookie("_kawltea"),
                                "_karmt" to response.cookie("_karmt"),
                                "_karmtea" to response.cookie("_karmtea")
                            )
                        )
                        if (onLoginSuccess!=null) onLoginSuccess()
                    }
                    else -> throw Error("로그인 도중 에러가 발생했습니다. ${response.body()}")
                }
            }
            else -> throw Error("API KEY 인증 과정에서 에러가 발생했습니다.")
        }
    }

    fun send(room:String,params:KakaoParams,type:String?) {
        val response = Jsoup.connect("https://sharer.kakao.com/talk/friends/picker/link").apply {
            referrer(referrer)
            cookies(cookies.filter { it.key in arrayOf("TIARA","_kawlt","_kawltea","_karmt","_karmtea") })
            data("app_key",apiKey)
            data("validation_action", type ?: "default")
            data("validation_params",Json.encodeToString(params))
            data("ka",kakaoStatic)
            data("lcba","")
            ignoreHttpErrors(true)
            method(Connection.Method.POST)
        }.execute()
        when(response.statusCode()) {
            400 -> throw IllegalStateException("템플릿 객체가 올바르지 않거나, Web 플랫폼에 등록되어 있는 도메인과 현재 도메인이 일치하지 않습니다.")
            200 -> {
                cookies.putAll(mapOf(
                    "KSHARER" to response.cookie("KSHARER"),
                    "using" to "true"
                ))
                val doc = response.parse()
                val validatedTalkLink = doc.select("#validatedTalkLink").attr("value")
                val csrfToken = doc.select("div").last().attr("ng-init").split("'")[1]
                val chats = Json.decodeFromString<Chats>(Jsoup.connect("https://sharer.kakao.com/api/talk/chats").apply {
                    referrer("https://sharer.kakao.com/talk/friends/picker/link")
                    header("Csrf-Token",csrfToken)
                    header("App-Key",apiKey)
                    cookies(cookies)
                    ignoreContentType(true)
                }.execute().body())

                val chat = chats.chats.find { it.title == room }
                    ?: throw IllegalArgumentException("방 이름 $room 을 찾을 수 없습니다.")

                val payload = Json.encodeToString(Payload(
                    receiverChatRoomMemberCount = listOf(1),
                    receiverIds = listOf(chat.id),
                    receiverType = "chat",
                    securityKey = chats.securityKey
                ))
                val requestBody = payload.substring(0..payload.length-2) + ",\"validatedTalkLink\":$validatedTalkLink}"

                Jsoup.connect("https://sharer.kakao.com/api/talk/message/link").apply {
                    referrer("https://sharer.kakao.com/talk/friends/picker/link")
                    header("Csrf-Token",csrfToken)
                    header("App-Key",apiKey)
                    header("Content-Type","application/json")
                    cookies(cookies.filter { it.key in arrayOf("KSHARER","TIARA","using","_kadu","_kadub","_kawlt","_kawltea","_karmt","_karmtea") })
                    requestBody(requestBody)
                    ignoreContentType(true)
                    ignoreHttpErrors(true)
                    method(Connection.Method.POST)
                }.execute()
            }
            else -> throw Error("템플릿 인증 과정 중에 알 수 없는 오류가 발생했습니다.")
        }
    }

    private fun encrypt(value:String,key:String):String = AESCipher.encrypt(value, key).toString()
}