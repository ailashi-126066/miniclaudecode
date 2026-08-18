// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.sandbox;

/**
 * Windows 沙箱实现（占位符）
 *
 * Windows 上的沙箱实现较为复杂，需要：
 * 1. AppContainer - 需要 JNA/JNI 调用 Windows API
 * 2. Windows Sandbox - 需要 Hyper-V 和管理员权限
 * 3. WSL - 可以复用 BwrapSandbox
 *
 * 当前实现为占位符，始终返回不可用状态。
 */
public class WindowsSandbox implements Sandbox {

    @Override
    public String wrap(String command, SandboxConfig config) {
        // Windows 沙箱需要使用 AppContainer 或 Windows Sandbox
        // 这需要 JNI/JNA 调用 Windows API，超出当前范围
        return command;
    }

    @Override
    public boolean isAvailable() {
        // Windows 沙箱暂不可用
        // TODO: 实现 AppContainer 或检测 Windows Sandbox
        return false;
    }
}
