// 触发 Operit MainActivity 的创建+销毁, 用于验证 MCPRepository 泄漏是否修复。
var log = [];
function p(s) {
  log.push(new Date().toISOString() + " " + s);
  try { files.write("/sdcard/leaktest.log", log.join("\n")); } catch (e) {}
}
p("script start");
try {
  app.startActivity({
    packageName: "com.xiaoyu.ai",
    className: "com.ai.assistance.operit.ui.main.MainActivity",
    flags: 0x10000000
  });
  p("started Operit MainActivity");
} catch (e) {
  p("startActivity err: " + e);
}
sleep(9000);
p("press back");
try { back(); } catch (e) { p("back err: " + e); }
sleep(2500);
try { back(); } catch (e) { p("back2 err: " + e); }
sleep(1500);
p("done");
