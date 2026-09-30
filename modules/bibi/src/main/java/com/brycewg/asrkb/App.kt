/**
 * 应用入口：初始化主题、多语言、统计与部分后台能力。
 *
 * 归属模块：根目录与通用
 *
 * @Hint for :modules:bibi library migration on Sep 30, 2026.
 *  ! As a library, this class is no longer registered as the application class.
 *  ! All formerly-here initialization has moved to `com.brycewg.asrkb.host.BibiLibrary.init()`,
 *  ! which is invoked by the host application (AutoJs6) in its own `App.onCreate`.
 *  ! zh-CN: 作为库模块后, 本类不再作为 application 类注册.
 *  ! 原初始化逻辑已迁移至 `com.brycewg.asrkb.host.BibiLibrary.init()`,
 *  ! 由宿主应用 (AutoJs6) 在其 `App.onCreate` 中调用.
 */
package com.brycewg.asrkb

import android.app.Application

class App : Application()
