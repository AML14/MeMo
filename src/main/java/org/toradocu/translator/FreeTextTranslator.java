package org.toradocu.translator;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.toradocu.conf.Configuration;
import org.toradocu.extractor.CommentContent;
import org.toradocu.extractor.DocumentedExecutable;
import org.toradocu.extractor.EquivalenceMatcher;
import org.toradocu.extractor.EquivalentMethodMatch;
import org.toradocu.extractor.FreeText;
import org.toradocu.util.ComplianceChecks;

public class FreeTextTranslator {

  /**
   * Translates a free text comment. For now only supports equivalences.
   *
   * @param freeTextComment an object representing a free text comment
   * @param excMember the executable member the comment belongs to
   * @return the translation, null if failed
   */
  public ArrayList<EquivalentMethodMatch> translate(
      FreeText freeTextComment, DocumentedExecutable excMember) {
    String commentText = freeTextComment.getComment().getText();
    String[] sentences = commentText.split("[.;] ");
    ArrayList<EquivalentMethodMatch> matches = new ArrayList<>();
    EquivalentMethodMatch equivalenceMatch = new EquivalentMethodMatch();

    for (String sentence : sentences) {
      // Let's avoid spurious comments...
      if (!sentence.isEmpty() && sentence.length() > 2) {
        // Verify comment contains equivalence declaration...
        equivalenceMatch = EquivalenceMatcher.getEquivalentOrSimilarMethod(sentence);
        if (!equivalenceMatch.getMethodSignature().isEmpty()) {
          if (equivalenceMatch.isSimilarity()) {
            translateConditionalEquivalence(excMember, equivalenceMatch, sentence);
          } else {
            // Exact equivalence
            matchEquivalentMethod(excMember, equivalenceMatch, "");
          }
        }
        matches.add(equivalenceMatch);
      }
    }
    return matches;
  }

  private void translateConditionalEquivalence(
      DocumentedExecutable excMember, EquivalentMethodMatch equivalenceMatch, String sentence) {
    String condition = extractCondition(sentence);
    if (condition != null) {
      String translation = "";
      List<PropositionSeries> extractedPropositions =
          Parser.parse(new CommentContent(condition), excMember);
      for (PropositionSeries propositions : extractedPropositions) {
        BasicTranslator.translate(propositions, excMember, sentence);
        translation = propositions.getTranslation();
        if (!translation.isEmpty()) {
          matchEquivalentMethod(excMember, equivalenceMatch, translation);
        }
      }
      if (translation.isEmpty()) {
        // Condition predicate could be expressed directly with a method signature, e.g.
        // "if list.isEmpty()" instead of the classic "if list is empty": look for signatures
        // FIXME this could be not the right place where to check this, mb as first attemp above?
      }
    }
  }

  /**
   * Finds the right code element match for the signature involved in the equivalence comment.
   *
   * @param excMember the executable member the comment belongs to
   * @param equivalenceMatch the final match, null if nothing found
   * @param condition
   */
  private void matchEquivalentMethod(
      DocumentedExecutable excMember, EquivalentMethodMatch equivalenceMatch, String condition) {
    String oracle;
    Matcher matcher = new Matcher();
    String methodName = equivalenceMatch.getSimpleName();

    // Extract every CodeElement associated with the method and the containing class of the method.
    Set<CodeElement<?>> codeElements = extractMethodCodeElements(excMember);
    Set<CodeElement<?>> matchingCodeEelem = matcher.subjectMatch(methodName, codeElements);

    if (matchingCodeEelem != null && !matchingCodeEelem.isEmpty()) {
      List<CodeElement<?>> sortedCodeElem = new ArrayList<>(matchingCodeEelem);

      Match theOne = matcher.reverseBestArgsTypeMatch(equivalenceMatch, excMember, sortedCodeElem);
      String negation = "";
      if (equivalenceMatch.isNegated()) {
        negation = "!";
      }
      if (theOne != null) {
        if (ComplianceChecks.primitiveTypes()
            .contains(excMember.getReturnType().getType().getTypeName())) {
          oracle = Configuration.RETURN_VALUE + "==" + negation + theOne.getBaseExpression();
        } else {
          oracle =
              Configuration.RETURN_VALUE + ".equals(" + negation + theOne.getBaseExpression() + ")";
        }

        if (ComplianceChecks.isEqSpecCompilable(excMember, oracle, condition)) {
          if (!condition.isEmpty()) {
            equivalenceMatch.setOracle("if (" + condition + ") {" + oracle + "}");
          } else {
            equivalenceMatch.setOracle(oracle);
          }
        }
      }
    }
  }

  private Set<CodeElement<?>> extractMethodCodeElements(DocumentedExecutable excMember) {
    Set<CodeElement<?>> collectedElements = new LinkedHashSet<>();
    Class<?> containingClass = excMember.getDeclaringClass();
    List<Executable> rawMethods =
        JavaElementsCollector.collectRawMethods(containingClass, excMember);
    collectedElements.addAll(
        JavaElementsCollector.getCodeElementsFromRawMethods(excMember, rawMethods));
    return collectedElements;
  }

  /**
   * Given a text, extracts a condition, if any
   *
   * @param text the text that may contain a condition
   * @return the extracted condition, if any
   */
  private String extractCondition(String text) {
    java.util.regex.Matcher matchIf =
        Pattern.compile("\\b" + "(?i)if" + "\\b", Pattern.CASE_INSENSITIVE).matcher(text);
    java.util.regex.Matcher matchWhen =
        Pattern.compile("\\b" + "(?i)when" + "\\b", Pattern.CASE_INSENSITIVE).matcher(text);

    int beginIndex = 0;
    int endIndex = 0;
    if (matchIf.find()) {
      beginIndex = matchIf.start();
    } else if (matchWhen.find()) {
      beginIndex = matchWhen.start();
    }

    // endIndex is the index of the SUBSEQUENT (to the if or when) comma or dot
    endIndex = text.indexOf(",");
    if (endIndex > beginIndex) {
      return text.substring(beginIndex, endIndex);
    }
    return null;
  }
}
