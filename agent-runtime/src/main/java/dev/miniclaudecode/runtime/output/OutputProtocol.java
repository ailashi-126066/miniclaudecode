package dev.miniclaudecode.runtime.output;

/** Validates and normalizes a tool-free model response before the loop is allowed to stop. */
public interface OutputProtocol {
  Evaluation evaluate(String response);

  record Evaluation(boolean valid, String finalText, String repairInstruction) {
    public static Evaluation valid(String finalText) {
      return new Evaluation(true, finalText, "");
    }

    public static Evaluation repair(String instruction) {
      return new Evaluation(false, "", instruction);
    }
  }
}
