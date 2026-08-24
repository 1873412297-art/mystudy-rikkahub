# Tavern 开场白切换栏吸顶间距修复设计

## 问题与证据

在 Huawei MNA-AL00 的开场白选择界面中，将正文向上滚动后，开场白切换栏没有贴紧聊天滚动区顶部，而是留下约 12 CSS px 的横向缝隙。缝隙会显示正在滚动的正文，造成顶部横条断裂和内容穿透的观感。

Chrome DevTools Protocol 对当前 WebView 的实测结果为：

- `#chat` 边界顶部为 `0`，`padding-top` 为 `12px`；
- `.opening-swipe-nav` 在吸顶状态下边界为 `[12px, 70px]`；
- 切换栏本身的 `position: sticky` 与 `top: 0` 生效，但 sticky 定位受滚动容器顶部 padding 约束。

根因是 `#chat` 的通用 `padding-top: 12px` 同时参与了开场白切换栏的 sticky 定位，并非按钮 margin、Compose 顶栏或 WebView 外层间距。

## 选定方案

仅对 `.mes.opening-swipe .opening-swipe-nav` 抵消滚动容器的 12px 顶部 padding，使其吸顶边界落在滚动区 `0px`。保留 `#chat` 的全局 padding，避免改变普通会话首屏、非开场白消息和其他沉浸式 Tavern 布局。

切换栏的按钮尺寸、计数器、横向布局、背景、正文间距和切换行为均保持不变。

## 不采用的方案

- 不删除或缩小 `#chat` 的全局顶部 padding：这会扩大到所有沉浸式会话的首屏布局。
- 不取消 sticky：用户滚动长开场白时仍需随时切换候选开场白。
- 不通过 Compose 外层 padding 补偿：问题发生在 WebView 文档内，外层补偿会同时移动正文和其他内容。

## 测试与验收

1. 先新增文档级回归测试，要求开场白切换栏的 sticky 顶部偏移明确抵消 `#chat` 的 12px 顶部 padding；验证测试在生产 CSS 修改前按预期失败。
2. 修改 `app/src/main/assets/html/tavern-conversation.html` 的开场白专属 CSS，使回归测试通过。
3. 运行相关 JVM 测试，并构建匹配设备 ABI 的 Debug APK。
4. 安装到 Huawei MNA-AL00，打开同一开场白选择会话，滚动至切换栏吸顶后截图。
5. 通过 CDP 再次读取矩形：`.opening-swipe-nav` 的顶部应为 `0px`；截图中不得再出现切换栏上方的正文缝隙，切换按钮仍可用且正文未被遮挡。

## 范围

本次只处理开场白切换栏吸顶时的顶部缝隙，不包含其他 Tavern 渲染、手势、卡片样式或聊天顶栏调整。
