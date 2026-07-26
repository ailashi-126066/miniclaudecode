package dev.miniclaudecode.extensions.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class StdioTestMcpServer {
  private StdioTestMcpServer() {}

  public static void main(String[] args) throws Exception {
    String line;
    try (BufferedReader input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8); ) {
      while ((line = input.readLine()) != null) {
        String response = TestMcpProtocol.respond(TestMcpProtocol.parse(line));
        if (!response.isEmpty()) {
          output.println(response);
        }
      }
    }
  }
}
