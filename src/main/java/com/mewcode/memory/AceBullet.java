package com.mewcode.memory;

/**
 * One durable ACE memory item: observed evidence, the model's inference, and
 * the command or other verification that supports it. Keeping those three
 * parts separate prevents an inference from being presented as a proven fact.
 */
public record AceBullet(String evidence, String inference, String verification) {
    public String render(String durableFact) {
        var out = new StringBuilder("### ACE\n\n");
        append(out, "Evidence", evidence);
        append(out, "Inference", inference);
        append(out, "Verification", verification);
        if (durableFact != null && !durableFact.isBlank()) {
            out.append("\n### Durable memory\n\n").append(durableFact.strip()).append('\n');
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String label, String value) {
        out.append("- **").append(label).append("**: ")
                .append(value == null || value.isBlank() ? "not provided" : value.strip())
                .append('\n');
    }
}
