// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.config;

/**
 * Remote 模式配置
 */
public class RemoteConfig {
    private String bindAddress = "127.0.0.1"; // 默认仅监听本地
    private String authToken; // API 认证令牌

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public boolean isAuthEnabled() { return authToken != null && !authToken.isBlank(); }
}
