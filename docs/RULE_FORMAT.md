# 声明式规则格式

## 目标

规则用于描述“在哪个应用、哪个版本、使用哪种已经编译进净启的识别方法”。规则不是脚本语言，不具备执行系统命令的能力。

当前 Kotlin 模型位于：

```text
app/src/main/java/app/jingqi/guard/rules/
```

## 应用规则

`AppRule` 包含：

- 稳定规则 ID；
- Android 包名；
- 显示名称；
- 节点安全策略；
- 可选的最低/最高 versionCode；
- 可选的固定视觉模板引用；
- 可选的已验证跳过控件资源 ID；
- 敏感应用标记。

节点安全策略：

- `VERIFIED`：经过实机验证，可使用明确文字或资源 ID；
- `GENERAL`：仅匹配明确跳过文字和可点击父节点；
- `FINANCIAL_EXACT`：只接受严格文字、资源 ID 和可点击父节点组合；
- `BLOCKED`：完全禁止自动点击和视觉识别。

## 视觉规则

`VisualSplashRule` 只能引用编译时固定的 `profileId`，并包含归一化点击位置、启动延迟、复查间隔、最大次数、有效时间窗口以及可选的精确 Activity 白名单。

视觉检测可以在有效启动窗口内重复，但每次都必须同时满足包名和同一次启动代次；规则声明了 Activity 白名单时还必须精确匹配。第一次命中后，本轮所有后续节点及视觉点击都会停止。

远程数据不能提供视觉 ROI、像素算法或任意点击坐标。新增视觉模板必须经过代码审查、APK 发布和实机测试。

## 未来签名包示例

```json
{
  "schemaVersion": 1,
  "revision": 42,
  "generatedAt": 1787443200000,
  "minimumAppVersionCode": 11,
  "rules": [
    {
      "id": "railway12306.splash.verified.v2",
      "packageName": "com.MobileTicket",
      "nodePolicy": "VERIFIED",
      "minimumVersionCode": 1000,
      "maximumVersionCode": 1999
    }
  ],
  "signatureAlgorithm": "Ed25519",
  "signature": "BASE64_SIGNATURE"
}
```

签名前必须使用确定性字段顺序和编码。解析器必须拒绝未知模式版本、重复规则 ID、无效包名、越界延迟、版本倒退和不支持的策略。

## 发布流程

1. 反馈进入待复现状态；
2. 在对应应用版本和设备上复现；
3. 编写最小规则；
4. 验证有广告时命中；
5. 验证无广告时不点击首页；
6. 对敏感分类执行额外安全审查；
7. 小范围灰度；
8. 观察误点击；
9. 全量发布或撤回。
