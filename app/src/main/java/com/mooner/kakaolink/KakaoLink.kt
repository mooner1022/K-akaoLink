package com.mooner.kakaolink

import android.util.Patterns
import com.eclipsesource.v8.V8
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class KakaoLink(
    private val apiKey:String,
    private val location:String
) {
    private var kakaoStatic:String = "sdk/1.36.6 os/javascript lang/en-US device/Win32 origin/"
    private lateinit var cookies:HashMap<String,String>
    private lateinit var referrer:String

    init {
        if (apiKey.length != 32) throw IllegalArgumentException("API KEY의 길이가 올바르지 않습니다.")
        if (!Patterns.WEB_URL.matcher(location).find()) throw IllegalArgumentException("도메인 주소의 형식이 올바르지 않습니다.")

        kakaoStatic += URLEncoder.encode(location,"utf-8")
    }

    fun login(email:String,pw:String) {
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
                    data("email",CryptoJs.encrypt(email,cryptoKey))
                    data("password",CryptoJs.encrypt(pw,cryptoKey))
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
                    }
                    else -> throw Error("로그인 도중 에러가 발생했습니다. ${response.body()}")
                }
            }
            else -> throw Error("API KEY 인증 과정에서 에러가 발생하였습니다.")
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

    private object CryptoJs {
        private var runtime:V8 = V8.createV8Runtime()
        fun encrypt(value:String,key:String):String {
            val result = runtime.executeStringScript(
                """
                    var CryptoJS=CryptoJS||function(c){function r(){}var t={},e=t.lib={},i=e.Base={extend:function(t){r.prototype=this;
                    var e=new r;return t&&e.mixIn(t),e.hasOwnProperty("init")||(e.init=function(){e.${'$'}super.init.apply(this,arguments)}),
                    (e.init.prototype=e).${'$'}super=this,e},create:function(){var t=this.extend();return t.init.apply(t,arguments),t},
                    init:function(){},mixIn:function(t){for(var e in t)t.hasOwnProperty(e)&&(this[e]=t[e]);t.hasOwnProperty("toString")&&
                    (this.toString=t.toString)},clone:function(){return this.init.prototype.extend(this)}},a=e.WordArray=i.extend({init:function(t,e){
                    t=this.words=t||[],this.sigBytes=null!=e?e:4*t.length},toString:function(t){return(t||o).stringify(this)},concat:function(t){
                    var e=this.words,r=t.words,i=this.sigBytes;if(t=t.sigBytes,this.clamp(),i%4)for(var n=0;n<t;n++)e[i+n>>>2]|=(r[n>>>2]>>>24-n%4*8&255)<<24-(i+n)%4*8;
                    else if(65535<r.length)for(n=0;n<t;n+=4)e[i+n>>>2]=r[n>>>2];else e.push.apply(e,r);return this.sigBytes+=t,this},clamp:function(){
                    var t=this.words,e=this.sigBytes;t[e>>>2]&=4294967295<<32-e%4*8,t.length=c.ceil(e/4)},clone:function(){var t=i.clone.call(this);
                    return t.words=this.words.slice(0),t},random:function(t){for(var e=[],r=0;r<t;r+=4)e.push(4294967296*c.random()|0);
                    return new a.init(e,t)}}),n=t.enc={},o=n.Hex={stringify:function(t){var e=t.words;t=t.sigBytes;for(var r=[],i=0;i<t;i++){
                    var n=e[i>>>2]>>>24-i%4*8&255;r.push((n>>>4).toString(16)),r.push((15&n).toString(16))}return r.join("")},parse:function(t){
                    for(var e=t.length,r=[],i=0;i<e;i+=2)r[i>>>3]|=parseInt(t.substr(i,2),16)<<24-i%8*4;return new a.init(r,e/2)}},s=n.Latin1={
                    stringify:function(t){var e=t.words;t=t.sigBytes;for(var r=[],i=0;i<t;i++)r.push(String.fromCharCode(e[i>>>2]>>>24-i%4*8&255));
                    return r.join("")},parse:function(t){for(var e=t.length,r=[],i=0;i<e;i++)r[i>>>2]|=(255&t.charCodeAt(i))<<24-i%4*8;
                    return new a.init(r,e)}},f=n.Utf8={stringify:function(t){try{return decodeURIComponent(escape(s.stringify(t)))}catch(t){
                    throw Error("Malformed UTF-8 data")}},parse:function(t){return s.parse(unescape(encodeURIComponent(t)))}},h=e.BufferedBlockAlgorithm=i.extend({
                    reset:function(){this._data=new a.init,this._nDataBytes=0},_append:function(t){"string"==typeof t&&(t=f.parse(t)),
                    this._data.concat(t),this._nDataBytes+=t.sigBytes},_process:function(t){var e=this._data,r=e.words,i=e.sigBytes,
                    n=this.blockSize,o=i/(4*n);if(t=(o=t?c.ceil(o):c.max((0|o)-this._minBufferSize,0))*n,i=c.min(4*t,i),t){for(var s=0;s<t;s+=n)
                    this._doProcessBlock(r,s);s=r.splice(0,t),e.sigBytes-=i}return new a.init(s,i)},clone:function(){var t=i.clone.call(this);
                    return t._data=this._data.clone(),t},_minBufferSize:0});e.Hasher=h.extend({cfg:i.extend(),init:function(t){this.cfg=this.cfg.extend(t),
                    this.reset()},reset:function(){h.reset.call(this),this._doReset()},update:function(t){return this._append(t),this._process(),this},
                    finalize:function(t){return t&&this._append(t),this._doFinalize()},blockSize:16,_createHelper:function(r){return function(t,e){
                    return new r.init(e).finalize(t)}},_createHmacHelper:function(r){return function(t,e){return new u.HMAC.init(r,e).finalize(t)}}});
                    var u=t.algo={};return t}(Math);function encrypt(t,e){return CryptoJS.AES.encrypt(t,e).toString()}!function(){var a=CryptoJS.lib.WordArray;
                    CryptoJS.enc.Base64={stringify:function(t){var e=t.words,r=t.sigBytes,i=this._map;t.clamp(),t=[];
                    for(var n=0;n<r;n+=3)for(var o=(e[n>>>2]>>>24-n%4*8&255)<<16|(e[n+1>>>2]>>>24-(n+1)%4*8&255)<<8|e[n+2>>>2]>>>24-(n+2)%4*8&255,s=0;s<4&&n+.75*s<r;s++)
                    t.push(i.charAt(o>>>6*(3-s)&63));if(e=i.charAt(64))for(;t.length%4;)t.push(e);return t.join("")},parse:function(t){var e=t.length,
                    r=this._map;!(o=r.charAt(64))||-1!=(o=t.indexOf(o))&&(e=o);for(var i,n,o=[],s=0,c=0;c<e;c++)c%4&&(i=r.indexOf(t.charAt(c-1))<<c%4*2,
                    n=r.indexOf(t.charAt(c))>>>6-c%4*2,o[s>>>2]|=(i|n)<<24-s%4*8,s++);return a.create(o,s)},_map:"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="}}(),
                    function(o){function m(t,e,r,i,n,o,s){return((t=t+(e&r|~e&i)+n+s)<<o|t>>>32-o)+e}function z(t,e,r,i,n,o,s){
                    return((t=t+(e&i|r&~i)+n+s)<<o|t>>>32-o)+e}function C(t,e,r,i,n,o,s){return((t=t+(e^r^i)+n+s)<<o|t>>>32-o)+e}function w(t,e,r,i,n,o,s){
                    return((t=t+(r^(e|~i))+n+s)<<o|t>>>32-o)+e}for(var t=CryptoJS,e=(i=t.lib).WordArray,r=i.Hasher,i=t.algo,E=[],n=0;n<64;n++)
                    E[n]=4294967296*o.abs(o.sin(n+1))|0;i=i.MD5=r.extend({_doReset:function(){this._hash=new e.init([1732584193,4023233417,2562383102,271733878])},
                    _doProcessBlock:function(t,e){for(var r=0;r<16;r++){var i=t[n=e+r];t[n]=16711935&(i<<8|i>>>24)|4278255360&(i<<24|i>>>8)}
                    var r=this._hash.words,n=t[e+0],i=t[e+1],o=t[e+2],s=t[e+3],c=t[e+4],a=t[e+5],f=t[e+6],h=t[e+7],u=t[e+8],p=t[e+9],d=t[e+10],
                    l=t[e+11],y=t[e+12],_=t[e+13],g=t[e+14],v=t[e+15],B=m(B=r[0],k=r[1],x=r[2],S=r[3],n,7,E[0]),S=m(S,B,k,x,i,12,E[1]),
                    x=m(x,S,B,k,o,17,E[2]),k=m(k,x,S,B,s,22,E[3]),B=m(B,k,x,S,c,7,E[4]),S=m(S,B,k,x,a,12,E[5]),x=m(x,S,B,k,f,17,E[6]),
                    k=m(k,x,S,B,h,22,E[7]),B=m(B,k,x,S,u,7,E[8]),S=m(S,B,k,x,p,12,E[9]),x=m(x,S,B,k,d,17,E[10]),k=m(k,x,S,B,l,22,E[11]),
                    B=m(B,k,x,S,y,7,E[12]),S=m(S,B,k,x,_,12,E[13]),x=m(x,S,B,k,g,17,E[14]),B=z(B,k=m(k,x,S,B,v,22,E[15]),x,S,i,5,E[16]),
                    S=z(S,B,k,x,f,9,E[17]),x=z(x,S,B,k,l,14,E[18]),k=z(k,x,S,B,n,20,E[19]),B=z(B,k,x,S,a,5,E[20]),S=z(S,B,k,x,d,9,E[21]),
                    x=z(x,S,B,k,v,14,E[22]),k=z(k,x,S,B,c,20,E[23]),B=z(B,k,x,S,p,5,E[24]),S=z(S,B,k,x,g,9,E[25]),x=z(x,S,B,k,s,14,E[26]),
                    k=z(k,x,S,B,u,20,E[27]),B=z(B,k,x,S,_,5,E[28]),S=z(S,B,k,x,o,9,E[29]),x=z(x,S,B,k,h,14,E[30]),B=C(B,k=z(k,x,S,B,y,20,E[31]),
                    x,S,a,4,E[32]),S=C(S,B,k,x,u,11,E[33]),x=C(x,S,B,k,l,16,E[34]),k=C(k,x,S,B,g,23,E[35]),B=C(B,k,x,S,i,4,E[36]),S=C(S,B,k,x,c,11,E[37]),
                    x=C(x,S,B,k,h,16,E[38]),k=C(k,x,S,B,d,23,E[39]),B=C(B,k,x,S,_,4,E[40]),S=C(S,B,k,x,n,11,E[41]),x=C(x,S,B,k,s,16,E[42]),
                    k=C(k,x,S,B,f,23,E[43]),B=C(B,k,x,S,p,4,E[44]),S=C(S,B,k,x,y,11,E[45]),x=C(x,S,B,k,v,16,E[46]),B=w(B,k=C(k,x,S,B,o,23,E[47]),x,S,n,6,E[48]),
                    S=w(S,B,k,x,h,10,E[49]),x=w(x,S,B,k,g,15,E[50]),k=w(k,x,S,B,a,21,E[51]),B=w(B,k,x,S,y,6,E[52]),S=w(S,B,k,x,s,10,E[53]),x=w(x,S,B,k,d,15,E[54]),
                    k=w(k,x,S,B,i,21,E[55]),B=w(B,k,x,S,u,6,E[56]),S=w(S,B,k,x,v,10,E[57]),x=w(x,S,B,k,f,15,E[58]),k=w(k,x,S,B,_,21,E[59]),B=w(B,k,x,S,c,6,E[60]),
                    S=w(S,B,k,x,l,10,E[61]),x=w(x,S,B,k,o,15,E[62]),k=w(k,x,S,B,p,21,E[63]);r[0]=r[0]+B|0,r[1]=r[1]+k|0,r[2]=r[2]+x|0,r[3]=r[3]+S|0},_doFinalize:function(){
                    var t=this._data,e=t.words,r=8*this._nDataBytes,i=8*t.sigBytes;e[i>>>5]|=128<<24-i%32;var n=o.floor(r/4294967296);
                    for(e[15+(64+i>>>9<<4)]=16711935&(n<<8|n>>>24)|4278255360&(n<<24|n>>>8),e[14+(64+i>>>9<<4)]=16711935&(r<<8|r>>>24)|4278255360&(r<<24|r>>>8),
                    t.sigBytes=4*(e.length+1),this._process(),e=(t=this._hash).words,r=0;r<4;r++)i=e[r],e[r]=16711935&(i<<8|i>>>24)|4278255360&(i<<24|i>>>8);return t},
                    clone:function(){var t=r.clone.call(this);return t._hash=this._hash.clone(),t}}),t.MD5=r._createHelper(i),t.HmacMD5=r._createHmacHelper(i)}(Math),function(){
                    var t=CryptoJS,e=t.lib,r=e.Base,f=e.WordArray,i=(e=t.algo).EvpKDF=r.extend({cfg:r.extend({keySize:4,hasher:e.MD5,iterations:1}),init:function(t){
                    this.cfg=this.cfg.extend(t)},compute:function(t,e){for(var r=(s=this.cfg).hasher.create(),i=f.create(),n=i.words,o=s.keySize,s=s.iterations;n.length<o;){
                    c&&r.update(c);var c=r.update(t).finalize(e);r.reset();for(var a=1;a<s;a++)c=r.finalize(c),r.reset();i.concat(c)}return i.sigBytes=4*o,i}});
                    t.EvpKDF=function(t,e,r){return i.create(r).compute(t,e)}}(),CryptoJS.lib.Cipher||function(){var t=(p=CryptoJS).lib,e=t.Base,s=t.WordArray,
                    r=t.BufferedBlockAlgorithm,i=p.enc.Base64,n=p.algo.EvpKDF,o=t.Cipher=r.extend({cfg:e.extend(),createEncryptor:function(t,e){
                    return this.create(this._ENC_XFORM_MODE,t,e)},createDecryptor:function(t,e){return this.create(this._DEC_XFORM_MODE,t,e)},
                    init:function(t,e,r){this.cfg=this.cfg.extend(r),this._xformMode=t,this._key=e,this.reset()},reset:function(){
                    r.reset.call(this),this._doReset()},process:function(t){return this._append(t),this._process()},finalize:function(t){
                    return t&&this._append(t),this._doFinalize()},keySize:4,ivSize:4,_ENC_XFORM_MODE:1,_DEC_XFORM_MODE:2,_createHelper:function(i){
                    return{encrypt:function(t,e,r){return("string"==typeof e?d:u).encrypt(i,t,e,r)},decrypt:function(t,e,r){return("string"==typeof e?d:u).decrypt(i,t,e,r)}}}});
                    function c(t,e,r){var i=this._iv;i?this._iv=void 0:i=this._prevBlock;for(var n=0;n<r;n++)t[e+n]^=i[n]}t.StreamCipher=o.extend({_doFinalize:function(){
                    return this._process(!0)},blockSize:1});var a=p.mode={},f=(t.BlockCipherMode=e.extend({createEncryptor:function(t,e){
                    return this.Encryptor.create(t,e)},createDecryptor:function(t,e){return this.Decryptor.create(t,e)},init:function(t,e){this._cipher=t,this._iv=e}})).extend();
                    f.Encryptor=f.extend({processBlock:function(t,e){var r=this._cipher,i=r.blockSize;c.call(this,t,e,i),r.encryptBlock(t,e),this._prevBlock=t.slice(e,e+i)}}),
                    f.Decryptor=f.extend({processBlock:function(t,e){var r=this._cipher,i=r.blockSize,n=t.slice(e,e+i);r.decryptBlock(t,e),c.call(this,t,e,i),
                    this._prevBlock=n}}),a=a.CBC=f,f=(p.pad={}).Pkcs7={pad:function(t,e){for(var r=4*e,i=(r=r-t.sigBytes%r)<<24|r<<16|r<<8|r,n=[],o=0;o<r;o+=4)n.push(i);
                    r=s.create(n,r),t.concat(r)},unpad:function(t){t.sigBytes-=255&t.words[t.sigBytes-1>>>2]}},t.BlockCipher=o.extend({cfg:o.cfg.extend({mode:a,padding:f}),
                    reset:function(){o.reset.call(this);var t,e=(r=this.cfg).iv,r=r.mode;this._xformMode==this._ENC_XFORM_MODE?t=r.createEncryptor:(t=r.createDecryptor,
                    this._minBufferSize=1),this._mode=t.call(r,this,e&&e.words)},_doProcessBlock:function(t,e){this._mode.processBlock(t,e)},_doFinalize:function(){
                    var t,e=this.cfg.padding;return this._xformMode==this._ENC_XFORM_MODE?(e.pad(this._data,this.blockSize),t=this._process(!0)):(t=this._process(!0),e.unpad(t)),t},blockSize:4});
                    var h=t.CipherParams=e.extend({init:function(t){this.mixIn(t)},toString:function(t){return(t||this.formatter).stringify(this)}}),
                    a=(p.format={}).OpenSSL={stringify:function(t){var e=t.ciphertext;return((t=t.salt)?s.create([1398893684,1701076831]).concat(t).concat(e):e).toString(i)},
                    parse:function(t){var e,r=(t=i.parse(t)).words;return 1398893684==r[0]&&1701076831==r[1]&&(e=s.create(r.slice(2,4)),r.splice(0,4),t.sigBytes-=16),
                    h.create({ciphertext:t,salt:e})}},u=t.SerializableCipher=e.extend({cfg:e.extend({format:a}),encrypt:function(t,e,r,i){i=this.cfg.extend(i);
                    var n=t.createEncryptor(r,i);return e=n.finalize(e),n=n.cfg,h.create({ciphertext:e,key:r,iv:n.iv,algorithm:t,mode:n.mode,padding:n.padding,
                    blockSize:t.blockSize,formatter:i.format})},decrypt:function(t,e,r,i){return i=this.cfg.extend(i),e=this._parse(e,i.format),t.createDecryptor(r,i).finalize(e.ciphertext)},
                    _parse:function(t,e){return"string"==typeof t?e.parse(t,this):t}}),p=(p.kdf={}).OpenSSL={execute:function(t,e,r,i){return i=i||s.random(8),
                    t=n.create({keySize:e+r}).compute(t,i),r=s.create(t.words.slice(e),4*r),t.sigBytes=4*e,h.create({key:t,iv:r,salt:i})}},
                    d=t.PasswordBasedCipher=u.extend({cfg:u.cfg.extend({kdf:p}),encrypt:function(t,e,r,i){return r=(i=this.cfg.extend(i)).kdf.execute(r,t.keySize,t.ivSize),i.iv=r.iv,
                    (t=u.encrypt.call(this,t,e,r.key,i)).mixIn(r),t},decrypt:function(t,e,r,i){return i=this.cfg.extend(i),e=this._parse(e,i.format),
                    r=i.kdf.execute(r,t.keySize,t.ivSize,e.salt),i.iv=r.iv,u.decrypt.call(this,t,e,r.key,i)}})}(),function(){
                    for(var t=CryptoJS,e=t.lib.BlockCipher,r=t.algo,s=[],i=[],n=[],o=[],c=[],a=[],f=[],h=[],u=[],p=[],d=[],l=0;l<256;l++)d[l]=l<128?l<<1:l<<1^283;for(var y=0,_=0,l=0;l<256;l++){
                    var g=(g=_^_<<1^_<<2^_<<3^_<<4)>>>8^255&g^99;s[y]=g;
                    var v=d[i[g]=y],B=d[v],S=d[B],x=257*d[g]^16843008*g;n[y]=x<<24|x>>>8,o[y]=x<<16|x>>>16,c[y]=x<<8|x>>>24,a[y]=x,x=16843009*S^65537*B^257*v^16843008*y,
                    f[g]=x<<24|x>>>8,h[g]=x<<16|x>>>16,u[g]=x<<8|x>>>24,p[g]=x,y?(y=v^d[d[d[S^v]]],_^=d[d[_]]):y=_=1}var k=[0,1,2,4,8,16,32,64,128,27,54],
                    r=r.AES=e.extend({_doReset:function(){for(var t,e=(i=this._key).words,r=i.sigBytes/4,i=4*((this._nRounds=r+6)+1),n=this._keySchedule=[],
                    o=0;o<i;o++)o<r?n[o]=e[o]:(t=n[o-1],o%r?6<r&&4==o%r&&(t=s[t>>>24]<<24|s[t>>>16&255]<<16|s[t>>>8&255]<<8|s[255&t]):(t=s[(t=t<<8|t>>>24)>>>24]<<24|s[t>>>16&255]<<16|s[t>>>8&255]<<8|s[255&t],
                    t^=k[o/r|0]<<24),n[o]=n[o-r]^t);for(e=this._invKeySchedule=[],r=0;r<i;r++)o=i-r,t=r%4?n[o]:n[o-4],e[r]=r<4||o<=4?t:f[s[t>>>24]]^h[s[t>>>16&255]]^u[s[t>>>8&255]]^p[s[255&t]]},
                    encryptBlock:function(t,e){this._doCryptBlock(t,e,this._keySchedule,n,o,c,a,s)},decryptBlock:function(t,e){var r=t[e+1];t[e+1]=t[e+3],t[e+3]=r,this._doCryptBlock(t,e,this._invKeySchedule,
                    f,h,u,p,i),r=t[e+1],t[e+1]=t[e+3],t[e+3]=r},_doCryptBlock:function(t,e,r,i,n,o,s,c){for(var a=this._nRounds,f=t[e]^r[0],h=t[e+1]^r[1],u=t[e+2]^r[2],p=t[e+3]^r[3],d=4,l=1;l<a;l++)
                    var y=i[f>>>24]^n[h>>>16&255]^o[u>>>8&255]^s[255&p]^r[d++],_=i[h>>>24]^n[u>>>16&255]^o[p>>>8&255]^s[255&f]^r[d++],g=i[u>>>24]^n[p>>>16&255]^o[f>>>8&255]^s[255&h]^r[d++],
                    p=i[p>>>24]^n[f>>>16&255]^o[h>>>8&255]^s[255&u]^r[d++],f=y,h=_,u=g;y=(c[f>>>24]<<24|c[h>>>16&255]<<16|c[u>>>8&255]<<8|c[255&p])^r[d++],
                    _=(c[h>>>24]<<24|c[u>>>16&255]<<16|c[p>>>8&255]<<8|c[255&f])^r[d++],g=(c[u>>>24]<<24|c[p>>>16&255]<<16|c[f>>>8&255]<<8|c[255&h])^r[d++],
                    p=(c[p>>>24]<<24|c[f>>>16&255]<<16|c[h>>>8&255]<<8|c[255&u])^r[d++],t[e]=y,t[e+1]=_,t[e+2]=g,t[e+3]=p},keySize:8});t.AES=e._createHelper(r)}();
                """.trimIndent().replace("\n","")
                +"encrypt('$value','$key');")
            return result
        }
    }
}