# taplusbridge

面向传送门 4.1.3 的 libxposed API 102 模块。按链接规则把识别到的网址交给指定应用打开，支持冻结目标的 Root 或 Shizuku 解冻。

## 使用

1. 安装 Release APK，在 LSPosed 启用 taplusbridge，并勾选「传送门」和「系统框架」作用域后重启。
2. 在规则页添加目标应用和域名或网址并保存。
3. 长按内容触发传送门，点击显示目标应用名称和图标的卡片打开。

不需要为单个目标应用编写适配。实际打开能力仍取决于目标应用是否处理该链接。

## 冻结模块本体

启用“冻结本体后继续运行”，等待系统确认配置，再用冰箱或 `pm disable` 冻结 taplusbridge。本体冻结时规则保存在系统框架；LSPosed 模块及其作用域需要保持启用。重启后的注入行为受设备框架和 ROM 影响。

## 构建

需要 JDK 21、Android SDK 37 和 Gradle Wrapper。Release 构建需要本机 `keystore.properties`，其中设置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。签名文件和属性不提交到仓库。

```powershell
.\gradlew.bat :app:lintRelease :app:assembleRelease
```
