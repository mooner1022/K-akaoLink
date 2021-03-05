# K-akaoLink

[![](https://jitpack.io/v/mooner1022/K-akaoLink.svg)](https://jitpack.io/#mooner1022/K-akaoLink)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/mooner1022/K-akaoLink/blob/main/LICENSE)

KakaoLink Module implemented for Java/Kotlin

Installation
------------
```gradle
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}

dependencies {
    implementation 'com.github.mooner1022:K-akaoLink:1.1.0'
}
```

Usage
------------
Basic usage
```kotlin
val kakaoLink = KakaoLink("javascript_api_key","https://your_domain_here") // init library

Thread { // create new thread for network
    kakaoLink.login("your_email@gmail.com","password") { // on login finished (optional)
        kakaoLink.send("YOUR_ROOM_NAME",
            KakaoParams(
                link_ver = "4.0",
                template_id = 10000,
                template_args = mapOf(
                    "title" to "테스트 제목"
                )
            ),"custom") // send template
    }
}.start() // start the thread
```

Another usage
```kotlin
val kakaoLink = KakaoLink("javascript_api_key","https://your_domain_here") // init library

Thread {
    kakaoLink.login("your_email@gmail.com","password") // login
}.start()

...

fun sendKaling(room:String,message:String) {
    Thread { // Call when it needs to be sent
        kakaoLink.send(room,
            KakaoParams(
                link_ver = "4.0",
                template_id = 10000,
                template_args = mapOf(
                    "message" to message
                )
            ),"custom") // send template
    }.start()
}
```
