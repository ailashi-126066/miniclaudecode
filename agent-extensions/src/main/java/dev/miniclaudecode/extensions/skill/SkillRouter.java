package dev.miniclaudecode.extensions.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-stage local skill router.
 *
 * <p>Stage one performs cheap token recall over compact metadata. Stage two reranks the bounded
 * candidate set with field-aware weights, keeping the full SKILL.md body out of the prompt until
 * the selected skill is explicitly loaded.
 */
public final class SkillRouter {
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_.-]{2,}");

  public List<RouteMatch> route(String intent, List<SkillDescriptor> skills, int limit) {
    if (intent == null || intent.isBlank()) {
      throw new IllegalArgumentException("skill routing intent must not be blank");
    }
    Objects.requireNonNull(skills, "skills must not be null");
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("skill routing limit must be between 1 and 20");
    }

    Set<String> query = tokens(intent);
    int candidateLimit = Math.max(12, limit * 4);
    return skills.stream()
        .map(skill -> recalled(query, skill))
        .filter(match -> match.recallScore() > 0)
        .sorted(
            Comparator.comparingInt(Candidate::recallScore)
                .reversed()
                .thenComparing(match -> match.skill().name()))
        .limit(candidateLimit)
        .map(candidate -> rerank(intent, query, candidate))
        .sorted(
            Comparator.comparingDouble(RouteMatch::score)
                .reversed()
                .thenComparing(match -> match.skill().name()))
        .limit(limit)
        .toList();
  }

  private static Candidate recalled(Set<String> query, SkillDescriptor skill) {
    Set<String> searchable = new LinkedHashSet<>();
    searchable.addAll(tokens(skill.name()));
    searchable.addAll(tokens(skill.description()));
    skill.tags().forEach(value -> searchable.addAll(tokens(value)));
    skill.triggers().forEach(value -> searchable.addAll(tokens(value)));
    int overlap = 0;
    for (String token : query) {
      if (searchable.contains(token)) {
        overlap++;
      }
    }
    return new Candidate(skill, overlap);
  }

  private static RouteMatch rerank(String intent, Set<String> query, Candidate candidate) {
    SkillDescriptor skill = candidate.skill();
    String normalizedIntent = intent.toLowerCase(Locale.ROOT);
    double score = candidate.recallScore();
    List<String> reasons = new ArrayList<>();

    if (normalizedIntent.contains(skill.name().toLowerCase(Locale.ROOT))) {
      score += 8;
      reasons.add("name");
    }
    int tagHits = overlap(query, skill.tags());
    int triggerHits = overlap(query, skill.triggers());
    int descriptionHits = overlap(query, List.of(skill.description()));
    int exampleHits = overlap(query, skill.examples());
    score += tagHits * 4.0 + triggerHits * 3.0 + descriptionHits * 2.0 + exampleHits;
    if (tagHits > 0) {
      reasons.add("tags=" + tagHits);
    }
    if (triggerHits > 0) {
      reasons.add("triggers=" + triggerHits);
    }
    if (descriptionHits > 0) {
      reasons.add("description=" + descriptionHits);
    }
    if (exampleHits > 0) {
      reasons.add("examples=" + exampleHits);
    }
    // Project-local skills intentionally win a close tie because they describe this workspace.
    score += skill.source().priority() * 0.15;
    return new RouteMatch(skill, score, candidate.recallScore(), List.copyOf(reasons));
  }

  private static int overlap(Set<String> query, List<String> values) {
    Set<String> tokens = new LinkedHashSet<>();
    values.forEach(value -> tokens.addAll(tokens(value)));
    int count = 0;
    for (String token : query) {
      if (tokens.contains(token)) {
        count++;
      }
    }
    return count;
  }

  private static Set<String> tokens(String value) {
    Set<String> values = new LinkedHashSet<>();
    Matcher matcher = TOKEN.matcher(Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String token = matcher.group();
      values.add(token);
      if (containsHan(token)) {
        for (int index = 0; index + 1 < token.length(); index++) {
          values.add(token.substring(index, index + 2));
        }
      }
    }
    return Set.copyOf(values);
  }

  private static boolean containsHan(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
  }

  private record Candidate(SkillDescriptor skill, int recallScore) {}

  public record RouteMatch(
      SkillDescriptor skill, double score, int recallScore, List<String> reasons) {
    public RouteMatch {
      Objects.requireNonNull(skill);
      reasons = List.copyOf(reasons);
    }
  }
}
