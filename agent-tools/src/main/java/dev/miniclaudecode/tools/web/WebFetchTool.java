package dev.miniclaudecode.tools.web;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class WebFetchTool implements AgentTool {
  private static final int DEFAULT_MAX_BYTES = 1048576;
  private static final int MAX_BYTES = 4194304;
  private static final int INLINE_BYTES = 32768;
  private static final int MAX_REDIRECTS = 5;
  private static final Set<String> METADATA_HOSTS =
      Set.of(
          "metadata.google.internal",
          "metadata.goog",
          "metadata",
          "metadata.azure.internal",
          "instance-data",
          "instance-data.ec2.internal");

  static {
    // The address this tool validates and the address HttpClient finally connects to are resolved
    // separately, which is a DNS-rebinding window: a hostname with a zero TTL can answer with a
    // public address for the check and a metadata address for the connection. Both resolutions go
    // through the JVM-wide InetAddress cache, so pinning a positive TTL makes them agree. The JDK
    // default is already 30s without a security manager, but it is a default rather than a
    // guarantee, so it is set explicitly here. Residual risk: an attacker who can make the cache
    // expire between the two resolutions; closing that fully requires connecting to a pinned
    // InetAddress, which java.net.http does not expose.
    if (java.security.Security.getProperty("networkaddress.cache.ttl") == null) {
      java.security.Security.setProperty("networkaddress.cache.ttl", "30");
    }
  }

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "web",
          "fetch",
          "Fetch bounded HTTP(S) text with redirect and SSRF protection",
          "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"timeoutSeconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":120},\"maxBytes\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"url\"]}",
          RiskLevel.MEDIUM,
          ToolEffect.READ_ONLY_EXTERNAL);
  private final HttpClient client;
  private final WebFetchTool.AddressResolver resolver;
  private final ToolResultStore resultStore;
  private final Clock clock;

  public WebFetchTool(ToolResultStore resultStore) {
    this(
        HttpClient.newBuilder()
            .followRedirects(Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15L))
            .build(),
        host -> List.of(InetAddress.getAllByName(host)),
        resultStore,
        Clock.systemUTC());
  }

  public WebFetchTool(
      HttpClient client,
      WebFetchTool.AddressResolver resolver,
      ToolResultStore resultStore,
      Clock clock) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      URI uri = URI.create(arguments.requiredText("url"));
      int timeout = arguments.optionalPositiveInt("timeoutSeconds", 30, 120);
      int maximumBytes = arguments.optionalPositiveInt("maxBytes", 1048576, 4194304);
      WebFetchTool.Access access = this.classify(uri);
      Optional<ToolResult> authorization = this.authorize(call, context, uri, access);
      return authorization.isPresent()
          ? CompletableFuture.completedFuture(authorization.orElseThrow())
          : CompletableFuture.supplyAsync(
              () -> this.fetch(call, uri, Duration.ofSeconds((long) timeout), maximumBytes));
    } catch (RuntimeException var9) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var9));
    }
  }

  private ToolResult fetch(ToolCall call, URI initial, Duration timeout, int maximumBytes) {
    URI current = initial;

    try {
      for (int redirects = 0; redirects <= 5; redirects++) {
        WebFetchTool.Access access = this.classify(current);
        if (access != WebFetchTool.Access.PUBLIC && !sameAuthority(initial, current)) {
          throw new SecurityException("redirect to a private or local network is blocked");
        }

        HttpRequest request =
            HttpRequest.newBuilder(current)
                .timeout(timeout)
                .header(
                    "Accept",
                    "text/plain,text/html,application/json,application/xml;q=0.9,*/*;q=0.1")
                .header("User-Agent", "MiniClaudeCode/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response =
            this.client.send(request, BodyHandlers.ofInputStream());
        if (!isRedirect(response.statusCode())) {
          ToolResult var14;
          try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
              throw new IllegalArgumentException("HTTP response exceeds maxBytes=" + maximumBytes);
            }

            Charset charset = responseCharset(response);
            String text = new String(bytes, charset);
            var14 =
                ToolResults.completed(
                    call,
                    "HTTP " + response.statusCode() + " " + current + "\n" + text,
                    Map.of(
                        "url",
                        current.toString(),
                        "status",
                        response.statusCode(),
                        "bytes",
                        bytes.length,
                        "contentType",
                        response.headers().firstValue("content-type").orElse("")),
                    this.resultStore,
                    32768);
          }

          return var14;
        }

        if (redirects == 5) {
          throw new IllegalArgumentException("too many HTTP redirects");
        }

        String location =
            response
                .headers()
                .firstValue("location")
                .orElseThrow(() -> new IllegalArgumentException("redirect has no Location"));

        try (InputStream ignored = response.body()) {
          current = current.resolve(location);
        }
      }

      throw new IllegalStateException("redirect loop terminated unexpectedly");
    } catch (IOException var19) {
      return ToolResults.failed(call, new IllegalArgumentException("HTTP fetch failed", var19));
    } catch (InterruptedException var20) {
      Thread.currentThread().interrupt();
      return ToolResults.failed(call, new IllegalStateException("HTTP fetch interrupted", var20));
    } catch (RuntimeException var21) {
      return ToolResults.failed(call, var21);
    }
  }

  private WebFetchTool.Access classify(URI uri) {
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException("web fetch supports only http and https");
    } else if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("URLs containing user info are not allowed");
    } else {
      String host = uri.getHost();
      if (host != null && !host.isBlank()) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(normalizedHost)) {
          throw new SecurityException("cloud metadata endpoints are blocked");
        } else {
          try {
            List<InetAddress> addresses = this.resolver.resolve(host);
            if (addresses.isEmpty()) {
              throw new IllegalArgumentException("host resolved to no addresses");
            } else {
              for (InetAddress address : addresses) {
                if (metadataAddress(address)) {
                  throw new SecurityException("cloud metadata endpoints are blocked");
                }
              }

              return addresses.stream().allMatch(WebFetchTool::publicAddress)
                  ? WebFetchTool.Access.PUBLIC
                  : WebFetchTool.Access.PRIVATE;
            }
          } catch (IOException var8) {
            throw new IllegalArgumentException("cannot resolve host: " + host, var8);
          }
        }
      } else {
        throw new IllegalArgumentException("URL must contain a host");
      }
    }
  }

  private Optional<ToolResult> authorize(
      ToolCall call, ToolContext context, URI uri, WebFetchTool.Access access) {
    boolean elevatedApproval =
        Boolean.TRUE.equals(context.attributes().get("elevatedApprovalRequired"));
    if (access == WebFetchTool.Access.PUBLIC && !elevatedApproval) {
      return Optional.empty();
    } else {
      Object requestValue = context.attributes().get("approvalRequest");
      Object decisionValue = context.attributes().get("approvalDecision");
      if (requestValue == null && decisionValue == null) {
        ApprovalRequest request =
            new ApprovalRequest(
                UUID.randomUUID(),
                call,
                RiskLevel.HIGH,
                uri.toString(),
                elevatedApproval
                    ? "Untrusted content in this turn requires approval before network access"
                    : "Private and local network access can expose internal services",
                Optional.empty(),
                Optional.empty(),
                Instant.now(this.clock));
        return Optional.of(
            new ToolResult(
                call.toolCallId(),
                Status.APPROVAL_REQUIRED,
                "Approval required before accessing private network URL: " + uri,
                Optional.empty(),
                Map.of("approvalRequest", request)));
      } else {
        if (!(requestValue instanceof ApprovalRequest request)
            || !(decisionValue instanceof ApprovalDecision decision)
            || !request.toolCall().equals(call)
            || !request.target().equals(uri.toString())
            || !request.approvalId().equals(decision.approvalId())) {
          throw new SecurityException("private network approval does not match this request");
        }

        return decision.choice() == Choice.REJECT
            ? Optional.of(
                new ToolResult(
                    call.toolCallId(),
                    Status.CANCELLED,
                    decision.feedback().orElse("private network access rejected"),
                    Optional.empty(),
                    Map.of()))
            : Optional.empty();
      }
    }
  }

  /**
   * Whether two URIs address the same origin. The scheme is part of the comparison so an approved
   * {@code https://internal} cannot be redirected to {@code http://internal} and silently
   * downgraded to plaintext while still counting as "already approved".
   */
  private static boolean sameAuthority(URI left, URI right) {
    return Objects.equals(
            left.getScheme() == null ? null : left.getScheme().toLowerCase(Locale.ROOT),
            right.getScheme() == null ? null : right.getScheme().toLowerCase(Locale.ROOT))
        && Objects.equals(
            left.getHost() == null ? null : left.getHost().toLowerCase(Locale.ROOT),
            right.getHost() == null ? null : right.getHost().toLowerCase(Locale.ROOT))
        && effectivePort(left) == effectivePort(right);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    } else {
      return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  private static Charset responseCharset(HttpResponse<?> response) {
    String contentType = response.headers().firstValue("content-type").orElse("");

    for (String part : contentType.split(";")) {
      String value = part.strip();
      if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
        try {
          return Charset.forName(value.substring("charset=".length()).strip());
        } catch (RuntimeException var8) {
          return StandardCharsets.UTF_8;
        }
      }
    }

    return StandardCharsets.UTF_8;
  }

  private static boolean metadataAddress(InetAddress address) {
    return NetworkAddressPolicy.isMetadata(address);
  }

  private static boolean publicAddress(InetAddress address) {
    return NetworkAddressPolicy.isPublic(address);
  }

  private static enum Access {
    PUBLIC,
    PRIVATE;
  }

  @FunctionalInterface
  public interface AddressResolver {
    List<InetAddress> resolve(String host) throws IOException;
  }
}
