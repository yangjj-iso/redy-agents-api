package io.github.yangjjiso.redyagents.core;

/** Estimates prompt budget units for text. An estimate is not a model tokenizer count. */
@FunctionalInterface
public interface TokenEstimator {
    int estimate(String text);
}
