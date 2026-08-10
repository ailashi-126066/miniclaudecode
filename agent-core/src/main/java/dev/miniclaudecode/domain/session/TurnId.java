package dev.miniclaudecode.domain.session;

public record TurnId(long value) implements Comparable<TurnId> {
  public TurnId(long value) {
    if (value < 1L) {
      throw new IllegalArgumentException("turnId must be greater than zero");
    } else {
      this.value = value;
    }
  }

  public static TurnId of(long value) {
    return new TurnId(value);
  }

  public TurnId next() {
    return new TurnId(Math.addExact(this.value, 1L));
  }

  public int compareTo(TurnId other) {
    return Long.compare(this.value, other.value);
  }

  @Override
  public String toString() {
    return Long.toString(this.value);
  }
}
