# Models

ModelsModule 负责 Provider 配置、SDK 模型、请求记录、计量、原生消息转换、同模型有界重试和最终请求校准。

模型工具选择器及备用模型均已删除；工具发现由 Tools 的 ToolSearch 负责。calibrate_model_request 只记录真正披露的工具定义及系统提示，供预算和执行校验使用。owner 创建并拥有本次模型资源，aclose 负责收尾。底层 HTTP 和模型协议由官方 SDK 执行。
