package net.tupenter.script;

/**
 * One registered variable's documentation: its full dotted name, the category
 * it renders under on its subject's help page, and a one-line blurb. Providers
 * that enumerate their names REQUIRE one of these at registration — a variable
 * without documentation is a compile error, not a doc-drift bug.
 */
public record VarDoc(String name, String category, String blurb) {
}
