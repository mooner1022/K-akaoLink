package com.mooner.kakaolink

import kotlinx.serialization.Serializable

@Serializable
data class KakaoParams(
    val link_ver: String,
    val template_id:Int? = null,
    val template_object:TemplateObject? = null,
    val template_args:Map<String,String>? = null
)

@Serializable
data class TemplateObject(
    val object_type:String,
    val button_title:String? = null,
    val content:TemplateContent,
    val buttons:List<TemplateButton>? = null
)

@Serializable
data class TemplateContent(
    val title:String,
    val image_url:String,
    val link:Link,
    val description:String
)

@Serializable
data class Link(
    val web_url:String,
    val mobile_web_url:String
)

@Serializable
data class TemplateButton(
    val title:String,
    val link:Link
)

@Serializable
data class Chats(
    val chats:List<ChatRoom>,
    val securityKey:String
)

@Serializable
data class ChatRoom(
    val id:String,
    val title:String,
    val memberCount:Int,
    val profileImageURLs:List<String>
)

@Serializable
data class Payload(
    val receiverChatRoomMemberCount:List<Int>,
    val receiverIds:List<String>,
    val receiverType:String,
    val securityKey:String
)