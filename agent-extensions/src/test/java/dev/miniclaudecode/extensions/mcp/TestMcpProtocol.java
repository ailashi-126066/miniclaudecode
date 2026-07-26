package dev.miniclaudecode.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class TestMcpProtocol {
  private static final ObjectMapper JSON = new ObjectMapper();

  private TestMcpProtocol() {}

  static ObjectNode parse(String request) throws Exception {
    return (ObjectNode) JSON.readTree(request);
  }

  static String respond(ObjectNode request) throws Exception {
    JsonNode id = request.get("id");
    if (id == null) {
      return "";
    } else {
      ObjectNode response = JSON.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id);
      ObjectNode result = response.putObject("result");
      String method = request.path("method").asText();
      switch (method) {
        case "initialize":
          initialize(request, result);
          break;
        case "tools/list":
          tools(result);
          break;
        case "tools/call":
          callTool(request, result);
          break;
        case "resources/list":
          resources(result);
          break;
        case "resources/templates/list":
          result.putArray("resourceTemplates");
          break;
        case "resources/read":
          readResource(result);
          break;
        case "prompts/list":
          prompts(result);
          break;
        case "prompts/get":
          getPrompt(result);
      }

      return JSON.writeValueAsString(response);
    }
  }

  private static void initialize(ObjectNode request, ObjectNode result) {
    result.put(
        "protocolVersion", request.path("params").path("protocolVersion").asText("2025-03-26"));
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools");
    capabilities.putObject("resources");
    capabilities.putObject("prompts");
    ObjectNode server = result.putObject("serverInfo");
    server.put("name", "MiniClaudeCode test MCP");
    server.put("version", "1.0");
  }

  private static void tools(ObjectNode result) {
    ObjectNode tool = result.putArray("tools").addObject();
    tool.put("name", "echo");
    tool.put("description", "Echo test input");
    ObjectNode schema = tool.putObject("inputSchema");
    schema.put("type", "object");
    schema.putObject("properties").putObject("text").put("type", "string");
    schema.putArray("required").add("text");
  }

  private static void callTool(ObjectNode request, ObjectNode result) {
    String text = request.path("params").path("arguments").path("text").asText();
    ObjectNode content = result.putArray("content").addObject();
    content.put("type", "text");
    content.put("text", "echo:" + text);
    result.put("isError", false);
  }

  private static void resources(ObjectNode result) {
    ObjectNode resource = result.putArray("resources").addObject();
    resource.put("uri", "test://guide");
    resource.put("name", "guide");
    resource.put("description", "test guide");
    resource.put("mimeType", "text/plain");
  }

  private static void readResource(ObjectNode result) {
    ObjectNode content = result.putArray("contents").addObject();
    content.put("uri", "test://guide");
    content.put("mimeType", "text/plain");
    content.put("text", "integration resource");
  }

  private static void prompts(ObjectNode result) {
    ObjectNode prompt = result.putArray("prompts").addObject();
    prompt.put("name", "review");
    prompt.put("description", "Review code");
    prompt.putArray("arguments");
  }

  private static void getPrompt(ObjectNode result) {
    result.put("description", "Review code");
    ArrayNode messages = result.putArray("messages");
    ObjectNode message = messages.addObject();
    message.put("role", "user");
    ObjectNode content = message.putObject("content");
    content.put("type", "text");
    content.put("text", "Review this code");
  }
}
