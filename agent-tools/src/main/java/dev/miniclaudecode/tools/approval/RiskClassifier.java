package dev.miniclaudecode.tools.approval;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.util.Locale;

public final class RiskClassifier {
  public RiskLevel classifyFileMutation(String normalizedTarget, RiskLevel baseRisk) {
    String path = normalizedTarget.toLowerCase(Locale.ROOT).replace('\\', '/');
    return !path.equals(".env")
            && !path.endsWith("/.env")
            && !path.contains("/.ssh/")
            && !path.endsWith("/id_rsa")
            && !path.endsWith("/id_ed25519")
            && !path.contains("credentials")
            && !path.contains("secrets")
        ? baseRisk
        : RiskLevel.HIGH;
  }
}
