package dev.miniclaudecode.runtime.output;

import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.OutputProtocolType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Selects output validation by model profile without importing provider implementations. */
public final class OutputProtocolRegistry {
  public static final String ATTRIBUTE = "outputProtocol";
  public static final String MAX_REPAIRS_ATTRIBUTE = "maxOutputRepairs";
  private final Map<OutputProtocolType, OutputProtocol> protocols;

  public OutputProtocolRegistry() {
    EnumMap<OutputProtocolType, OutputProtocol> values = new EnumMap<>(OutputProtocolType.class);
    values.put(OutputProtocolType.NATURAL_LANGUAGE, new NaturalLanguageOutputProtocol());
    values.put(OutputProtocolType.JSON, new JsonOutputProtocol());
    this.protocols = Map.copyOf(values);
  }

  public OutputProtocol.Evaluation evaluate(ModelRequest request, String response) {
    return this.protocol(request).evaluate(response);
  }

  public int maximumRepairs(ModelRequest request) {
    Object configured = request.attributes().get(MAX_REPAIRS_ATTRIBUTE);
    return configured instanceof Number number ? Math.max(0, Math.min(5, number.intValue())) : 1;
  }

  private OutputProtocol protocol(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    Object configured = request.attributes().get(ATTRIBUTE);
    OutputProtocolType type =
        configured instanceof OutputProtocolType output
            ? output
            : OutputProtocolType.parse(Objects.toString(configured, "natural-language"));
    return Objects.requireNonNull(this.protocols.get(type), "output protocol is not registered");
  }
}
