# 拾页 WebLite

基于**系统 WebView** 的轻量安卓浏览器，主打 **Android TV / 电视盒**：

- 不内置任何浏览器内核，直接用系统自带 WebView——电视上装第三方浏览器跑不起来的问题不存在
- 固定深色主题，**动态取色/模糊等特效默认关闭**（设置里留了总开关）
- **地址栏 + 工具栏整体位置可自定义**：置顶或置底，遥控器都够得着
- **硬件信息页**：CPU / GPU（EGL 直查）/ 内存 / 屏幕分辨率与刷新率 / 存储 / WebView 内核版本，单独一页
- 多标签页、书签、历史、桌面模式 UA、四大搜索引擎可切换
- 声明了 LEANBACK_LAUNCHER，电视桌面直接出现图标；手机上也能正常用

## 构建

```bash
git clone https://github.com/yingfing/weblite.git
cd weblite
./gradlew :app:assembleDebug
```

CI 每次 push 自动构建 APK 并上传到 Actions；打 `v*` tag 自动发 Release。

## 首次使用（电视）

1. `adb connect 电视IP:5555 && adb install app-debug.apk`
2. 电视桌面找到「拾页 WebLite」，遥控器方向键即可操作
3. 建议：设置里把工具栏切到「底部」，搜索引擎按喜好选

## 已知边界

- 电视遥控器没有鼠标，部分重交互网页体验受限（后续版本做焦点优化与手势模式）
- 文件下载暂用系统 DownloadManager 兜底（v1.1 接 UI 进度）
- 无 HTML5 全屏视频按钮（v1.1）

## License

MIT
