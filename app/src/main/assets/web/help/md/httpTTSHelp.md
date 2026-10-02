# 在线朗读规则说明

* 在线朗读规则为url规则,同书源url

## 引擎类型

引擎类型为「在线 HTTP」时按下文的 url 规则请求音频。

引擎类型为「微软 Edge（免费）」时走后端内置协议，直接调用微软 Edge 浏览器「大声朗读」的在线神经语音（协议移植自开源项目 edge-tts，MIT）。该引擎**无需 API Key、无需注册**，但需要联网，音色在 `voice` 字段填写，常用的有：

| 音色 | 说明 |
| --- | --- |
| zh-CN-XiaoxiaoNeural | 晓晓·女声，通用 |
| zh-CN-XiaoyiNeural | 晓伊·女声，活泼 |
| zh-CN-YunxiNeural | 云希·男声，阳光 |
| zh-CN-YunjianNeural | 云健·男声，浑厚 |
| zh-CN-YunyangNeural | 云扬·男声，新闻 |
| zh-CN-liaoning-XiaobeiNeural | 晓北·东北话 |
| zh-CN-shaanxi-XiaoniNeural | 晓妮·陕西话 |
| zh-TW-HsiaoChenNeural | 曉臻·台湾国语 |

点「选择音色」可拉取微软在线音色全量列表。朗读速度使用朗读设置里的语速，10 为原速，映射范围 -50% ~ +200%；音调与音量暂不支持调节。若提示握手被拒（HTTP 403），一般是系统时间不准，校准系统时间后重试。

## js参数

```
speakText //朗读文本
speakSpeed //朗读速度,5-50
```

## 例

```
http://tts.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{String((speakSpeed + 5) / 10 + 4)}}&per=5003&cuid=baidu_speech_demo&idx=1&cod=2&lan=zh&ctp=1&pdt=1&vol=5&pit=5&_res_tag_=audio"
}
```

## 字段说明

* url: 请求规则,支持method/body/headers等url选项,同书源
* Content-Type: 服务端音频MIME,如audio/mpeg
* 并发率: 请求间隔,同书源concurrentRate
* 登录url/登录UI/登录检测js: 登录协议,同书源
* 请求头: 全局请求头JSON
* jsLib: 共享给本引擎所有JS(含登录检测js)调用的公共JS库文本;函数内需通过this取java/source等对象
* 启用CookieJar: 请求自动保存/携带Cookie,登录或session接口需开启
* 段落间隔: 段落间插入静音毫秒数
