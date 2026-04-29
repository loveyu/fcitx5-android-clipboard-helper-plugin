# 小企鹅输入法剪贴板助手插件

适用于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的剪贴板监听插件，可在每次剪贴板变动时将内容以 HTTP POST 转发至指定地址。

## 功能

- 通过 `IClipboardEntryTransformer` 接口被动接收剪贴板内容，**无需额外权限**
- 支持 HTTP 和 HTTPS，HTTPS 可选择忽略证书验证
- 数据以 Base64 编码后封装为 JSON 发送
- 内置功能开关，关闭后不记录日志也不转发
- 设置页面实时显示 fcitx5-android 连接状态
- 设置页面实时更新上次读取的剪贴板内容（通过 SharedPreferences 监听器）
- 内置调试日志查看（最近 3 条），可手动刷新

## 工作原理

```
fcitx5-android
  │  发现插件（查询 plugin.MANIFEST intent）
  │  绑定 MainService（plugin.SERVICE intent）
  ▼
MainService.onBind()
  │  反向绑定 fcitx5-android 的 IPC 服务
  ▼
IFcitxRemoteService
  │  注册 IClipboardEntryTransformer
  ▼
每次剪贴板变动
  │  fcitx5-android 调用 transform(clipboardText)
  │  插件将内容 Base64 编码后 HTTP POST 到配置地址
  └→ 返回原文（不修改剪贴板内容）
```

## 构建

### 调试版（推荐用于开发测试）

```bash
./gradlew assembleDebug
```

调试版使用 `.debug` 应用 ID 后缀，并连接 fcitx5-android **调试版**（包名 `org.fcitx.fcitx5.android.debug`）。两个应用均使用 Android 标准调试密钥签名，证书自动匹配。

### 发布版

```bash
./gradlew assembleRelease
```

发布版连接 fcitx5-android **正式版**（包名 `org.fcitx.fcitx5.android`）。

> ⚠️ **签名要求**：fcitx5-android 的 IPC 权限为 `protectionLevel="signature"` 级别，要求插件与 fcitx5-android 使用**相同的签名证书**。若证书不匹配，`bindService()` 会因权限校验失败，导致无法注册 Transformer，从而无法监听剪贴板。
>
> - 若与**自行编译的 fcitx5-android 发布版**配合使用：使用相同密钥签名两个应用即可。
> - 若与 **fcitx5-android 调试版**配合使用：无需配置发布密钥，构建系统会自动回退到调试密钥。
> - 若与**官方渠道发布的 fcitx5-android**配合使用：由于无法获得其签名密钥，无法直接使用。

#### 配置签名密钥

在项目根目录的 `local.properties` 中添加（文件不纳入版本控制）：

```properties
signing.storeFile=/path/to/keystore.p12
signing.storePassword=your_store_password
signing.keyAlias=your_key_alias
signing.keyPassword=your_key_password
```

或通过环境变量传入（适合 CI/CD）：

```
SIGNING_STORE_FILE=/path/to/keystore.p12
SIGNING_STORE_PASSWORD=your_store_password
SIGNING_KEY_ALIAS=your_key_alias
SIGNING_KEY_PASSWORD=your_key_password
```

若以上均未配置，发布版将自动回退使用调试密钥，方便在本地配合 fcitx5-android 调试版进行测试。

可使用项目提供的脚本生成密钥库：

```bash
bash generate-keystore.sh
```

## 使用说明

1. 安装 APK 后，在 fcitx5-android 设置 → 插件管理中确认本插件显示为“已加载”，然后至少启动一次输入法
2. 打开本插件的设置页面，填写 POST 目标地址并点击保存
3. 开启功能开关（默认开启）
4. 设置页面的「刷新」按钮可手动更新连接状态与调试日志

说明：若状态显示“插件已加载，等待输入法启动后绑定服务”，表示插件已被 fcitx5-android 识别，但当前还没有进入输入法运行态；切换到小企鹅输入法并实际唤起一次后会自动绑定。

## POST 数据格式

每次剪贴板变动时，向配置地址发送如下 JSON（`Content-Type: application/json`）：

```json
{"base64": "<剪贴板内容的 Base64 编码（无换行）>"}
```

示例（剪贴板内容为 `hello`）：

```json
{"base64": "aGVsbG8="}
```

## 调试

设置页面底部的「调试日志」区域显示最近 3 条来自本插件的 logcat 日志（Tag：`ClipboardPlugin`、`ClipboardHttpSender`），点击「刷新」按钮更新。

也可通过 adb 实时查看：

```bash
adb logcat -s ClipboardPlugin ClipboardHttpSender
```

## 许可证

[LICENSE](LICENSE)
